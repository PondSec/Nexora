package network.nexora.trading;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable BIN and timed bid auctions. Item snapshots remain in the database until claimed. */
public final class AuctionHouseService {
    public enum Mode { BIN, BID }
    public enum Outcome { CREATED, PURCHASED, BID_PLACED, CANCELLED, INSUFFICIENT_FUNDS, NOT_FOUND,
        NOT_ACTIVE, NOT_OWNER, BID_TOO_LOW, INVALID, ALREADY_APPLIED }
    public record Listing(UUID id, UUID seller, Mode mode, long priceMinor, long currentBidMinor,
                          UUID highestBidder, long endsAt, String itemType) {}
    public record Result(Outcome outcome, UUID auctionId, String message) {}
    public record ItemClaim(String claimId, UUID auctionId, byte[] snapshot) {
        public ItemClaim { snapshot = snapshot.clone(); }
        @Override public byte[] snapshot() { return snapshot.clone(); }
    }
    private static final int SELLER_FEE_BPS = 200;
    private static final int BUYER_FEE_BPS = 100;
    private static final long MAX_DURATION = 7L * 24 * 60 * 60 * 1000;
    private final MarketplaceDatabase database;

    public AuctionHouseService(MarketplaceDatabase database) { this.database = Objects.requireNonNull(database); }

    public Result create(UUID seller, Mode mode, long price, long durationMillis, String itemType,
                         byte[] snapshot, String operationKey, long now) throws SQLException {
        return create(seller, mode, price, durationMillis, itemType, snapshot, null, operationKey, now);
    }

    /** Creates a listing for a unique standardized item instance; the id is shared with Bazaar reservations. */
    public Result create(UUID seller, Mode mode, long price, long durationMillis, String itemType,
                         byte[] snapshot, String instanceId, String operationKey, long now) throws SQLException {
        if (seller == null || mode == null || price <= 0 || durationMillis <= 0 || durationMillis > MAX_DURATION
                || itemType == null || itemType.isBlank() || snapshot == null || snapshot.length == 0
                || operationKey == null || operationKey.isBlank()) return new Result(Outcome.INVALID, null, "Invalid listing");
        UUID id = UUID.randomUUID();
        long endsAt = Math.addExact(now, durationMillis);
        return database.transact(c -> {
            if (!database.profileExists(c, seller)) return new Result(Outcome.NOT_OWNER, null, "Unknown seller profile");
            if (instanceId != null) {
                if (instanceId.isBlank()) return new Result(Outcome.INVALID, null, "Invalid item instance id");
                try (var reservation = c.prepareStatement("INSERT INTO market_item_reservations(instance_id,market,listing_id,owner_uuid,state,created_at,updated_at) VALUES(?,'AUCTION',?,?,'LISTED',?,?)")) {
                    reservation.setString(1, instanceId); reservation.setString(2, id.toString()); reservation.setString(3, seller.toString());
                    reservation.setLong(4, now); reservation.setLong(5, now);
                    try { reservation.executeUpdate(); }
                    catch (SQLException conflict) {
                        if (conflict.getMessage() != null && conflict.getMessage().toLowerCase(java.util.Locale.ROOT).contains("constraint"))
                            return new Result(Outcome.INVALID, null, "This item is already held by a marketplace listing or claim");
                        throw conflict;
                    }
                }
            }
            long fee = mode == Mode.BIN ? MoneyMath.fee(price, SELLER_FEE_BPS) : MoneyMath.fee(price, SELLER_FEE_BPS);
            if (fee > 0) {
                var debit = database.updateBalance(c, seller, -fee);
                if (debit == MarketplaceDatabase.ApplyResult.INSUFFICIENT_FUNDS)
                    return new Result(Outcome.INSUFFICIENT_FUNDS, null, "Not enough Coins for listing fee");
                if (debit != MarketplaceDatabase.ApplyResult.APPLIED) throw new SQLException("Could not charge auction listing fee");
                database.ledger(c, seller, -fee, "Auction listing fee", now, "auction:" + operationKey + ":listing-fee");
            }
            String sha = sha256(snapshot);
            try (var insert = c.prepareStatement("INSERT INTO auctions(auction_id,seller_uuid,mode,status,start_price_minor,bin_price_minor,current_bid_minor,starts_at,ends_at,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?,0,?,?,?,?)")) {
                insert.setString(1, id.toString()); insert.setString(2, seller.toString()); insert.setString(3, mode.name());
                insert.setLong(4, price); if (mode == Mode.BIN) insert.setLong(5, price); else insert.setNull(5, java.sql.Types.INTEGER);
                insert.setLong(6, now); insert.setLong(7, endsAt); insert.setLong(8, now); insert.setLong(9, now); insert.executeUpdate();
            }
            try (var insert = c.prepareStatement("INSERT INTO auction_items(auction_id,snapshot_version,item_type,item_snapshot,item_sha256,created_at) VALUES(?,1,?,?,?,?)")) {
                insert.setString(1, id.toString()); insert.setString(2, itemType); insert.setBytes(3, snapshot); insert.setString(4, sha); insert.setLong(5, now); insert.executeUpdate();
            }
            try (var insert = c.prepareStatement("INSERT INTO auction_transactions(transaction_id,idempotency_key,auction_id,actor_uuid,action,amount_minor,created_at) VALUES(?,?,?,?,?,?,?)")) {
                insert.setString(1, "auction:" + operationKey); insert.setString(2, operationKey); insert.setString(3, id.toString());
                insert.setString(4, seller.toString()); insert.setString(5, "CREATE_" + mode.name()); insert.setLong(6, price); insert.setLong(7, now); insert.executeUpdate();
            }
            return new Result(Outcome.CREATED, id, "Auction listing created");
        });
    }

    public Result buyBin(UUID buyer, UUID auctionId, String operationKey, long now) throws SQLException {
        return database.transact(c -> {
            var auction = load(c, auctionId);
            if (auction == null) return new Result(Outcome.NOT_FOUND, auctionId, "Listing not found");
            if (!auction.mode.equals("BIN") || !auction.status.equals("ACTIVE") || auction.endsAt <= now)
                return new Result(Outcome.NOT_ACTIVE, auctionId, "BIN listing is no longer active");
            if (auction.seller.equals(buyer)) return new Result(Outcome.NOT_OWNER, auctionId, "You cannot buy your own listing");
            long buyerFee = MoneyMath.fee(auction.price, BUYER_FEE_BPS);
            long total = Math.addExact(auction.price, buyerFee);
            var debit = database.updateBalance(c, buyer, -total);
            if (debit == MarketplaceDatabase.ApplyResult.INSUFFICIENT_FUNDS)
                return new Result(Outcome.INSUFFICIENT_FUNDS, auctionId, "Not enough Coins");
            if (debit != MarketplaceDatabase.ApplyResult.APPLIED) throw new SQLException("Could not debit BIN buyer");
            database.ledger(c, buyer, -auction.price, "Auction BIN purchase", now, "auction:" + operationKey + ":buyer");
            if (buyerFee > 0) database.ledger(c, buyer, -buyerFee, "Auction purchase fee", now, "auction:" + operationKey + ":fee");
            settleSeller(c, auction.seller, auction.price, now, "auction:" + operationKey + ":seller");
            try (var update = c.prepareStatement("UPDATE auctions SET status='SOLD',highest_bidder_uuid=?,current_bid_minor=?,updated_at=?,version=version+1 WHERE auction_id=? AND status='ACTIVE'")) {
                update.setString(1, buyer.toString()); update.setLong(2, auction.price); update.setLong(3, now); update.setString(4, auctionId.toString());
                if (update.executeUpdate() != 1) throw new SQLException("Auction state changed during BIN purchase");
            }
            itemClaim(c, buyer, auctionId, "auction:" + operationKey + ":item", now);
            history(c, auctionId, auction.seller, buyer, auction.price, "SOLD", now);
            transaction(c, operationKey, auctionId, buyer, "BUY_BIN", total, now);
            return new Result(Outcome.PURCHASED, auctionId, "BIN purchase complete");
        });
    }

    public Result bid(UUID bidder, UUID auctionId, long amount, String operationKey, long now) throws SQLException {
        if (amount <= 0 || operationKey == null || operationKey.isBlank()) return new Result(Outcome.INVALID, auctionId, "Invalid bid");
        return database.transact(c -> {
            var a = load(c, auctionId);
            if (a == null) return new Result(Outcome.NOT_FOUND, auctionId, "Auction not found");
            if (!a.mode.equals("BID") || !a.status.equals("ACTIVE") || a.endsAt <= now) return new Result(Outcome.NOT_ACTIVE, auctionId, "Auction is no longer active");
            if (a.seller.equals(bidder)) return new Result(Outcome.NOT_OWNER, auctionId, "You cannot bid on your own auction");
            long minimum = a.currentBid == 0 ? a.price : Math.addExact(a.currentBid, Math.max(1, MoneyMath.fee(a.currentBid, 500)));
            if (amount < minimum) return new Result(Outcome.BID_TOO_LOW, auctionId, "Minimum bid is " + minimum);
            var debit = database.updateBalance(c, bidder, -amount);
            if (debit == MarketplaceDatabase.ApplyResult.INSUFFICIENT_FUNDS) return new Result(Outcome.INSUFFICIENT_FUNDS, auctionId, "Not enough Coins");
            if (debit != MarketplaceDatabase.ApplyResult.APPLIED) throw new SQLException("Could not reserve bid escrow");
            database.ledger(c, bidder, -amount, "Auction bid escrow", now, "auction:" + operationKey + ":debit");
            if (a.highestBidder != null) {
                try (var update = c.prepareStatement("UPDATE auction_bids SET status='OUTBID',escrow_minor=0 WHERE auction_id=? AND bidder_uuid=? AND status='LEADING'")) {
                    update.setString(1, auctionId.toString()); update.setString(2, a.highestBidder.toString()); update.executeUpdate();
                }
                if (database.updateBalance(c, a.highestBidder, a.currentBid) != MarketplaceDatabase.ApplyResult.APPLIED)
                    throw new SQLException("Could not refund outbid escrow");
                database.ledger(c, a.highestBidder, a.currentBid, "Auction bid outbid refund", now, "auction:" + operationKey + ":refund");
            }
            UUID bidId = UUID.randomUUID();
            try (var insert = c.prepareStatement("INSERT INTO auction_bids(bid_id,auction_id,bidder_uuid,amount_minor,status,escrow_minor,created_at) VALUES(?,?,?,?,'LEADING',?,?)")) {
                insert.setString(1, bidId.toString()); insert.setString(2, auctionId.toString()); insert.setString(3, bidder.toString()); insert.setLong(4, amount); insert.setLong(5, amount); insert.setLong(6, now); insert.executeUpdate();
            }
            try (var update = c.prepareStatement("UPDATE auctions SET current_bid_minor=?,highest_bidder_uuid=?,updated_at=?,version=version+1 WHERE auction_id=? AND status='ACTIVE'")) {
                update.setLong(1, amount); update.setString(2, bidder.toString()); update.setLong(3, now); update.setString(4, auctionId.toString()); update.executeUpdate();
            }
            transaction(c, operationKey, auctionId, bidder, "BID", amount, now);
            return new Result(Outcome.BID_PLACED, auctionId, "Bid is now leading");
        });
    }

    public int settleExpired(long now) throws SQLException {
        List<UUID> expired = new ArrayList<>();
        try (Connection c = database.openConnection(); var q = c.prepareStatement("SELECT auction_id FROM auctions WHERE status='ACTIVE' AND ends_at<=? ORDER BY ends_at LIMIT 500")) {
            q.setLong(1, now); try (var rows = q.executeQuery()) { while (rows.next()) expired.add(UUID.fromString(rows.getString(1))); }
        }
        int settled = 0;
        for (UUID id : expired) if (settleOne(id, now)) settled++;
        return settled;
    }

    private boolean settleOne(UUID id, long now) throws SQLException {
        return database.transact(c -> {
            var a = load(c, id); if (a == null || !a.status.equals("ACTIVE") || a.endsAt > now) return false;
            if (a.highestBidder != null) {
                long sellerNet = settleSeller(c, a.seller, a.currentBid, now, "auction:expiry:" + id + ":seller");
                try (var update = c.prepareStatement("UPDATE auctions SET status='SOLD',updated_at=?,version=version+1 WHERE auction_id=?")) { update.setLong(1, now); update.setString(2, id.toString()); update.executeUpdate(); }
                try (var update = c.prepareStatement("UPDATE auction_bids SET status='WON',escrow_minor=0 WHERE auction_id=? AND bidder_uuid=? AND status='LEADING'")) { update.setString(1, id.toString()); update.setString(2, a.highestBidder.toString()); update.executeUpdate(); }
                itemClaim(c, a.highestBidder, id, "auction:expiry:" + id + ":item", now);
                history(c, id, a.seller, a.highestBidder, a.currentBid, "SOLD", now);
            } else {
                try (var update = c.prepareStatement("UPDATE auctions SET status='ENDED',updated_at=?,version=version+1 WHERE auction_id=?")) { update.setLong(1, now); update.setString(2, id.toString()); update.executeUpdate(); }
                itemClaim(c, a.seller, id, "auction:expiry:" + id + ":return", now);
                history(c, id, a.seller, null, 0, "UNSOLD", now);
            }
            return true;
        });
    }

    public List<Listing> browse(int limit, long now) throws SQLException {
        if (limit < 1 || limit > 54) throw new IllegalArgumentException("Invalid page size");
        List<Listing> listings = new ArrayList<>();
        try (Connection c = database.openConnection(); var q = c.prepareStatement("SELECT a.auction_id,a.seller_uuid,a.mode,a.start_price_minor,a.current_bid_minor,a.highest_bidder_uuid,a.ends_at,i.item_type FROM auctions a JOIN auction_items i ON i.auction_id=a.auction_id WHERE a.status='ACTIVE' AND a.ends_at>? ORDER BY a.created_at DESC LIMIT ?")) {
            q.setLong(1, now); q.setInt(2, limit);
            try (var rows = q.executeQuery()) { while (rows.next()) listings.add(new Listing(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), Mode.valueOf(rows.getString(3)), rows.getLong(4), rows.getLong(5), rows.getString(6) == null ? null : UUID.fromString(rows.getString(6)), rows.getLong(7), rows.getString(8))); }
        }
        return List.copyOf(listings);
    }

    public Listing getActive(UUID auctionId, long now) throws SQLException {
        try (Connection c = database.openConnection(); var q = c.prepareStatement("SELECT a.auction_id,a.seller_uuid,a.mode,a.start_price_minor,a.current_bid_minor,a.highest_bidder_uuid,a.ends_at,i.item_type FROM auctions a JOIN auction_items i ON i.auction_id=a.auction_id WHERE a.auction_id=? AND a.status='ACTIVE' AND a.ends_at>?")) {
            q.setString(1, auctionId.toString()); q.setLong(2, now);
            try (var rows = q.executeQuery()) {
                if (!rows.next()) return null;
                return new Listing(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), Mode.valueOf(rows.getString(3)),
                        rows.getLong(4), rows.getLong(5), rows.getString(6) == null ? null : UUID.fromString(rows.getString(6)), rows.getLong(7), rows.getString(8));
            }
        }
    }

    public List<Listing> myListings(UUID seller, int limit, long now) throws SQLException {
        if (limit < 1 || limit > 54) throw new IllegalArgumentException("Invalid page size");
        List<Listing> listings = new ArrayList<>();
        try (Connection c = database.openConnection(); var q = c.prepareStatement("SELECT a.auction_id,a.seller_uuid,a.mode,a.start_price_minor,a.current_bid_minor,a.highest_bidder_uuid,a.ends_at,i.item_type FROM auctions a JOIN auction_items i ON i.auction_id=a.auction_id WHERE a.seller_uuid=? AND a.status='ACTIVE' AND a.ends_at>? ORDER BY a.created_at DESC LIMIT ?")) {
            q.setString(1, seller.toString()); q.setLong(2, now); q.setInt(3, limit);
            try (var rows = q.executeQuery()) { while (rows.next()) listings.add(new Listing(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), Mode.valueOf(rows.getString(3)), rows.getLong(4), rows.getLong(5), rows.getString(6) == null ? null : UUID.fromString(rows.getString(6)), rows.getLong(7), rows.getString(8))); }
        }
        return List.copyOf(listings);
    }

    public List<Listing> myBids(UUID bidder, int limit, long now) throws SQLException {
        if (limit < 1 || limit > 54) throw new IllegalArgumentException("Invalid page size");
        List<Listing> listings = new ArrayList<>();
        try (Connection c = database.openConnection(); var q = c.prepareStatement("SELECT a.auction_id,a.seller_uuid,a.mode,a.start_price_minor,a.current_bid_minor,a.highest_bidder_uuid,a.ends_at,i.item_type FROM auctions a JOIN auction_items i ON i.auction_id=a.auction_id WHERE a.highest_bidder_uuid=? AND a.status='ACTIVE' AND a.ends_at>? ORDER BY a.ends_at LIMIT ?")) {
            q.setString(1, bidder.toString()); q.setLong(2, now); q.setInt(3, limit);
            try (var rows = q.executeQuery()) { while (rows.next()) listings.add(new Listing(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), Mode.valueOf(rows.getString(3)), rows.getLong(4), rows.getLong(5), UUID.fromString(rows.getString(6)), rows.getLong(7), rows.getString(8))); }
        }
        return List.copyOf(listings);
    }

    /** Cancels an unbid active listing and returns its exact snapshot through the normal claim flow. */
    public Result cancel(UUID seller, UUID auctionId, String operationKey, long now) throws SQLException {
        return database.transact(c -> {
            var a = load(c, auctionId);
            if (a == null) return new Result(Outcome.NOT_FOUND, auctionId, "Listing not found");
            if (!a.status.equals("ACTIVE") || a.endsAt <= now) return new Result(Outcome.NOT_ACTIVE, auctionId, "Listing is no longer active");
            if (!a.seller.equals(seller)) return new Result(Outcome.NOT_OWNER, auctionId, "You do not own this listing");
            if (a.highestBidder != null || a.currentBid > 0) return new Result(Outcome.INVALID, auctionId, "A listing with a bid cannot be cancelled");
            try (var update = c.prepareStatement("UPDATE auctions SET status='CANCELLED',updated_at=?,version=version+1 WHERE auction_id=? AND status='ACTIVE' AND highest_bidder_uuid IS NULL AND current_bid_minor=0")) {
                update.setLong(1, now); update.setString(2, auctionId.toString());
                if (update.executeUpdate() != 1) return new Result(Outcome.NOT_ACTIVE, auctionId, "Listing changed; reopen your listings");
            }
            itemClaim(c, seller, auctionId, "auction:" + operationKey + ":cancel-item", now);
            history(c, auctionId, seller, null, 0, "CANCELLED", now);
            transaction(c, operationKey, auctionId, seller, "CANCEL", 0, now);
            return new Result(Outcome.CANCELLED, auctionId, "Listing cancelled; item added to claims");
        });
    }

    public List<ItemClaim> pendingClaims(UUID owner) throws SQLException {
        List<ItemClaim> claims = new ArrayList<>();
        try (Connection c = database.openConnection(); var q = c.prepareStatement("SELECT claim_id,auction_id,item_snapshot FROM auction_claimables WHERE profile_uuid=? AND asset_type='ITEM' AND claimed_at IS NULL ORDER BY created_at LIMIT 100")) {
            q.setString(1, owner.toString()); try (var rows = q.executeQuery()) { while (rows.next()) claims.add(new ItemClaim(rows.getString(1), UUID.fromString(rows.getString(2)), rows.getBytes(3))); }
        }
        return List.copyOf(claims);
    }

    public void markClaimed(UUID owner, String claimId, long now) throws SQLException {
        database.transact(c -> {
            String auctionId;
            try (var update = c.prepareStatement("UPDATE auction_claimables SET claimed_at=? WHERE claim_id=? AND profile_uuid=? AND asset_type='ITEM' AND claimed_at IS NULL")) {
                update.setLong(1, now); update.setString(2, claimId); update.setString(3, owner.toString());
                if (update.executeUpdate() != 1) throw new SQLException("Item claim is missing or already claimed");
            }
            try (var query = c.prepareStatement("SELECT auction_id FROM auction_claimables WHERE claim_id=? AND profile_uuid=?")) {
                query.setString(1, claimId); query.setString(2, owner.toString());
                try (var row = query.executeQuery()) { if (!row.next()) throw new SQLException("Claim disappeared"); auctionId = row.getString(1); }
            }
            try (var delete = c.prepareStatement("DELETE FROM market_item_reservations WHERE market='AUCTION' AND listing_id=?")) {
                delete.setString(1, auctionId); delete.executeUpdate();
            }
            return null;
        });
    }

    private long settleSeller(Connection c, UUID seller, long gross, long now, String key) throws SQLException {
        long fee = MoneyMath.fee(gross, SELLER_FEE_BPS), net = gross - fee;
        if (net > 0) {
            if (database.updateBalance(c, seller, net) != MarketplaceDatabase.ApplyResult.APPLIED) throw new SQLException("Could not settle auction seller proceeds");
            database.ledger(c, seller, net, "Auction sale proceeds", now, key);
        }
        return net;
    }

    private void itemClaim(Connection c, UUID owner, UUID auction, String source, long now) throws SQLException {
        try (var insert = c.prepareStatement("INSERT INTO auction_claimables(claim_id,profile_uuid,auction_id,asset_type,item_snapshot,source_transaction_id,created_at) SELECT ?,?,?,'ITEM',item_snapshot,?,? FROM auction_items WHERE auction_id=?")) {
            insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, owner.toString()); insert.setString(3, auction.toString());
            insert.setString(4, source); insert.setLong(5, now); insert.setString(6, auction.toString());
            if (insert.executeUpdate() != 1) throw new SQLException("Auction item snapshot missing");
        }
    }

    private void history(Connection c, UUID id, UUID seller, UUID buyer, long price, String result, long now) throws SQLException {
        try (var insert = c.prepareStatement("INSERT INTO auction_history(auction_id,seller_uuid,buyer_uuid,final_price_minor,result,closed_at) VALUES(?,?,?,?,?,?)")) {
            insert.setString(1, id.toString()); insert.setString(2, seller.toString()); insert.setString(3, buyer == null ? null : buyer.toString()); insert.setLong(4, price); insert.setString(5, result); insert.setLong(6, now); insert.executeUpdate();
        }
    }

    private void transaction(Connection c, String key, UUID auction, UUID actor, String action, long amount, long now) throws SQLException {
        try (var insert = c.prepareStatement("INSERT INTO auction_transactions(transaction_id,idempotency_key,auction_id,actor_uuid,action,amount_minor,created_at) VALUES(?,?,?,?,?,?,?)")) {
            insert.setString(1, "auction:" + key); insert.setString(2, key); insert.setString(3, auction.toString()); insert.setString(4, actor.toString()); insert.setString(5, action); insert.setLong(6, amount); insert.setLong(7, now); insert.executeUpdate();
        }
    }

    private AuctionRow load(Connection c, UUID id) throws SQLException {
        try (var q = c.prepareStatement("SELECT seller_uuid,mode,status,COALESCE(bin_price_minor,start_price_minor),current_bid_minor,highest_bidder_uuid,ends_at FROM auctions WHERE auction_id=?")) {
            q.setString(1, id.toString()); try (var row = q.executeQuery()) { return row.next() ? new AuctionRow(UUID.fromString(row.getString(1)), row.getString(2), row.getString(3), row.getLong(4), row.getLong(5), row.getString(6) == null ? null : UUID.fromString(row.getString(6)), row.getLong(7)) : null; }
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private record AuctionRow(UUID seller, String mode, String status, long price, long currentBid, UUID highestBidder, long endsAt) {}
}
