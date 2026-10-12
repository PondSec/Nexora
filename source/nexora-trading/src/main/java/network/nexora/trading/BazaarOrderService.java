package network.nexora.trading;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Transactional player order service. Bukkit item removal/delivery is handled by the Paper adapter. */
public final class BazaarOrderService {
    public enum Outcome { OPENED, PARTIAL, FILLED, CANCELLED, INSUFFICIENT_FUNDS, INSUFFICIENT_LIQUIDITY,
        INVALID_PRODUCT, NOT_OWNER, NOT_OPEN, ALREADY_APPLIED }
    public record PlaceResult(Outcome outcome, UUID orderId, long filled, long remaining, String message) {}
    public record Claim(String claimId, String productId, long quantity, long amountMinor) {}
    public record ItemClaim(String claimId, String productId, long quantity) {}
    public record BookLevel(long priceMinor, long quantity, int orderCount) {}
    public record OrderView(UUID orderId, String productId, OrderSide side, long unitPriceMinor,
                            long remaining, long filled, String status, long createdAt) {}

    private final MarketplaceDatabase database;
    public BazaarOrderService(MarketplaceDatabase database) { this.database = Objects.requireNonNull(database); }

    public boolean registerProduct(String productId, String materialKey, long now) throws SQLException {
        if (productId == null || productId.isBlank() || materialKey == null || materialKey.isBlank())
            throw new IllegalArgumentException("Invalid product registration");
        try (Connection connection = database.openConnection(); var statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO bazaar_products(product_id,asset_key,created_at) VALUES(?,?,?)")) {
            statement.setString(1, productId); statement.setString(2, materialKey); statement.setLong(3, now);
            statement.executeUpdate();
        }
        try (Connection connection = database.openConnection(); var query = connection.prepareStatement(
                "SELECT asset_key,enabled FROM bazaar_products WHERE product_id=?")) {
            query.setString(1, productId);
            try (var row = query.executeQuery()) { return row.next() && materialKey.equals(row.getString(1)) && row.getInt(2) == 1; }
        }
    }

    /** SELL quantity must already have been removed from the player's inventory and is recorded as item escrow. */
    public PlaceResult place(UUID owner, String productId, OrderSide side, long unitPriceMinor,
                              long quantity, boolean requireFullFill, String operationId, long now) throws SQLException {
        Objects.requireNonNull(owner); Objects.requireNonNull(side);
        if (unitPriceMinor <= 0 || quantity <= 0 || operationId == null || operationId.isBlank())
            throw new IllegalArgumentException("Invalid Bazaar order");
        UUID orderId = UUID.randomUUID();
        try {
            return database.transact(connection -> placeTx(connection, owner, productId, side, unitPriceMinor,
                    quantity, requireFullFill, operationId, orderId, now));
        } catch (Rejected rejection) {
            return new PlaceResult(rejection.outcome, null, 0, 0, rejection.getMessage());
        }
    }

    private PlaceResult placeTx(Connection c, UUID owner, String product, OrderSide side, long price, long quantity,
                                boolean full, String operationId, UUID id, long now) throws SQLException {
        if (!database.profileExists(c, owner)) throw reject(Outcome.NOT_OWNER, "Unknown profile");
        try (var q = c.prepareStatement("SELECT 1 FROM bazaar_products WHERE product_id=? AND enabled=1")) {
            q.setString(1, product); try (var row = q.executeQuery()) { if (!row.next()) throw reject(Outcome.INVALID_PRODUCT, "Unknown or disabled Bazaar product"); }
        }
        List<Resting> resting = opposing(c, owner, product, side, price);
        long possible = 0;
        for (Resting order : resting) possible = Math.addExact(possible, order.remaining);
        if (full && possible < quantity) throw reject(Outcome.INSUFFICIENT_LIQUIDITY, "Insufficient opposing liquidity");

        long reservation = side == OrderSide.BUY ? Math.multiplyExact(price, quantity) : 0;
        long creationFee = side == OrderSide.BUY ? BazaarAccounting.buyOrderCreationFee(price, quantity) : 0;
        if (side == OrderSide.BUY) {
            MarketplaceDatabase.ApplyResult debit = database.updateBalance(c, owner, -Math.addExact(reservation, creationFee));
            if (debit == MarketplaceDatabase.ApplyResult.INSUFFICIENT_FUNDS) throw reject(Outcome.INSUFFICIENT_FUNDS, "Not enough Coins for order escrow and fee");
            if (debit != MarketplaceDatabase.ApplyResult.APPLIED) throw new SQLException("Could not reserve order Coins");
            database.ledger(c, owner, -reservation, "Bazaar buy-order escrow", now, "bazaar:" + operationId + ":reserve");
            if (creationFee > 0) database.ledger(c, owner, -creationFee, "Bazaar buy-order fee", now, "bazaar:" + operationId + ":fee");
        }
        try (var insert = c.prepareStatement("INSERT INTO bazaar_orders(order_id,profile_uuid,product_id,side,unit_price_minor,original_quantity,remaining_quantity,filled_quantity,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,'OPEN',?,?)")) {
            insert.setString(1, id.toString()); insert.setString(2, owner.toString()); insert.setString(3, product);
            insert.setString(4, side.name()); insert.setLong(5, price); insert.setLong(6, quantity);
            insert.setLong(7, quantity); insert.setLong(8, now); insert.setLong(9, now); insert.executeUpdate();
        }
        String transactionId = "bazaar:" + operationId;
        try (var tx = c.prepareStatement("INSERT INTO bazaar_transactions(transaction_id,idempotency_key,transaction_type,profile_uuid,order_id,amount_minor,quantity,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            tx.setString(1, transactionId); tx.setString(2, operationId); tx.setString(3, "PLACE_" + side.name());
            tx.setString(4, owner.toString()); tx.setString(5, id.toString()); tx.setLong(6, reservation); tx.setLong(7, quantity); tx.setLong(8, now); tx.executeUpdate();
        }
        try (var escrow = c.prepareStatement("INSERT INTO bazaar_escrow(escrow_id,order_id,profile_uuid,asset_type,product_id,amount_minor,quantity,updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
            escrow.setString(1, UUID.randomUUID().toString()); escrow.setString(2, id.toString()); escrow.setString(3, owner.toString());
            escrow.setString(4, side == OrderSide.BUY ? "COINS" : "ITEMS"); escrow.setString(5, side == OrderSide.BUY ? null : product);
            escrow.setLong(6, reservation); escrow.setLong(7, side == OrderSide.SELL ? quantity : 0); escrow.setLong(8, now); escrow.executeUpdate();
        }
        long filled = 0;
        for (Resting counter : resting) {
            if (filled == quantity) break;
            long matchQuantity = Math.min(quantity - filled, counter.remaining);
            settleFill(c, id, owner, side, product, price, counter, matchQuantity, now, operationId);
            filled += matchQuantity;
        }
        long remaining = quantity - filled;
        String status = remaining == 0 ? "FILLED" : filled > 0 ? "PARTIAL" : "OPEN";
        try (var update = c.prepareStatement("UPDATE bazaar_orders SET remaining_quantity=?,filled_quantity=?,status=?,updated_at=?,version=version+1 WHERE order_id=?")) {
            update.setLong(1, remaining); update.setLong(2, filled); update.setString(3, status); update.setLong(4, now); update.setString(5, id.toString()); update.executeUpdate();
        }
        return new PlaceResult(remaining == 0 ? Outcome.FILLED : filled > 0 ? Outcome.PARTIAL : Outcome.OPENED,
                id, filled, remaining, "Order accepted");
    }

    private void settleFill(Connection c, UUID incomingId, UUID incomingOwner, OrderSide incomingSide,
                            String product, long incomingLimit, Resting counter, long quantity,
                            long now, String placeOperation) throws SQLException {
        UUID buyId = incomingSide == OrderSide.BUY ? incomingId : counter.id;
        UUID sellId = incomingSide == OrderSide.SELL ? incomingId : counter.id;
        UUID buyer = incomingSide == OrderSide.BUY ? incomingOwner : counter.owner;
        UUID seller = incomingSide == OrderSide.SELL ? incomingOwner : counter.owner;
        long execution = counter.price;
        long gross = Math.multiplyExact(execution, quantity);
        long buyerLimit = incomingSide == OrderSide.BUY ? incomingLimit : counter.price;
        boolean buyerIsIncoming = incomingSide == OrderSide.BUY;
        if (!buyerIsIncoming) buyerLimit = orderPrice(c, buyId);
        BazaarAccounting.FillSettlement settlement = BazaarAccounting.settleFill(buyerLimit, execution, quantity,
                buyerIsIncoming || counter.side == OrderSide.BUY);
        debitBuyEscrow(c, buyId, quantity, buyerLimit, settlement.buyerImprovementRefundMinor(), buyer, now,
                "bazaar-fill:" + placeOperation + ":" + counter.id + ":" + quantity);
        debitSellEscrow(c, sellId, quantity, now);
        if (settlement.sellerNetMinor() > 0) {
            if (database.updateBalance(c, seller, settlement.sellerNetMinor()) != MarketplaceDatabase.ApplyResult.APPLIED)
                throw new SQLException("Could not settle Bazaar seller proceeds");
            database.ledger(c, seller, settlement.sellerNetMinor(), "Bazaar sale proceeds", now,
                    "bazaar:fill:" + placeOperation + ":seller:" + counter.id);
        }
        String fillId = UUID.randomUUID().toString();
        try (var insert = c.prepareStatement("INSERT INTO bazaar_order_fills(fill_id,operation_id,buy_order_id,sell_order_id,buyer_uuid,seller_uuid,product_id,quantity,unit_price_minor,gross_minor,seller_fee_minor,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, fillId); insert.setString(2, "bazaar-fill:" + placeOperation + ":" + counter.id + ":" + quantity);
            insert.setString(3, buyId.toString()); insert.setString(4, sellId.toString()); insert.setString(5, buyer.toString());
            insert.setString(6, seller.toString()); insert.setString(7, product); insert.setLong(8, quantity);
            insert.setLong(9, execution); insert.setLong(10, gross); insert.setLong(11, settlement.sellerFeeMinor()); insert.setLong(12, now); insert.executeUpdate();
        }
        try (var claim = c.prepareStatement("INSERT INTO bazaar_claimables(claim_id,profile_uuid,asset_type,product_id,amount_minor,quantity,source_transaction_id,created_at) VALUES(?,?,'ITEMS',?,0,?,?,?)")) {
            claim.setString(1, UUID.randomUUID().toString()); claim.setString(2, buyer.toString()); claim.setString(3, product);
            claim.setLong(4, quantity); claim.setString(5, fillId); claim.setLong(6, now); claim.executeUpdate();
        }
        try (var history = c.prepareStatement("INSERT INTO bazaar_price_history(product_id,fill_id,price_minor,quantity,traded_at) VALUES(?,?,?,?,?)")) {
            history.setString(1, product); history.setString(2, fillId); history.setLong(3, execution); history.setLong(4, quantity); history.setLong(5, now); history.executeUpdate();
        }
        updateFillOrder(c, buyId, quantity, now);
        updateFillOrder(c, sellId, quantity, now);
    }

    private void debitBuyEscrow(Connection c, UUID order, long quantity, long limit, long improvement,
                                UUID buyer, long now, String key) throws SQLException {
        long reserve = Math.multiplyExact(limit, quantity);
        try (var update = c.prepareStatement("UPDATE bazaar_escrow SET amount_minor=amount_minor-?,updated_at=?,version=version+1 WHERE order_id=? AND asset_type='COINS' AND amount_minor>=?")) {
            update.setLong(1, reserve); update.setLong(2, now); update.setString(3, order.toString()); update.setLong(4, reserve);
            if (update.executeUpdate() != 1) throw new SQLException("Buy order escrow is insufficient");
        }
        if (improvement > 0) {
            if (database.updateBalance(c, buyer, improvement) != MarketplaceDatabase.ApplyResult.APPLIED) throw new SQLException("Price-improvement refund failed");
            database.ledger(c, buyer, improvement, "Bazaar price improvement refund", now, key + ":refund");
        }
    }

    private void debitSellEscrow(Connection c, UUID order, long quantity, long now) throws SQLException {
        try (var update = c.prepareStatement("UPDATE bazaar_escrow SET quantity=quantity-?,updated_at=?,version=version+1 WHERE order_id=? AND asset_type='ITEMS' AND quantity>=?")) {
            update.setLong(1, quantity); update.setLong(2, now); update.setString(3, order.toString()); update.setLong(4, quantity);
            if (update.executeUpdate() != 1) throw new SQLException("Sell order item escrow is insufficient");
        }
    }

    private void updateFillOrder(Connection c, UUID id, long qty, long now) throws SQLException {
        try (var update = c.prepareStatement("UPDATE bazaar_orders SET remaining_quantity=remaining_quantity-?,filled_quantity=filled_quantity+?,status=CASE WHEN remaining_quantity-?=0 THEN 'FILLED' ELSE 'PARTIAL' END,updated_at=?,version=version+1 WHERE order_id=? AND remaining_quantity>=?")) {
            update.setLong(1, qty); update.setLong(2, qty); update.setLong(3, qty); update.setLong(4, now); update.setString(5, id.toString()); update.setLong(6, qty);
            if (update.executeUpdate() != 1) throw new SQLException("Order quantity changed during fill");
        }
    }

    private long orderPrice(Connection c, UUID order) throws SQLException {
        try (var query = c.prepareStatement("SELECT unit_price_minor FROM bazaar_orders WHERE order_id=?")) {
            query.setString(1, order.toString()); try (var row = query.executeQuery()) { if (!row.next()) throw new SQLException("Missing buy order"); return row.getLong(1); }
        }
    }

    private List<Resting> opposing(Connection c, UUID owner, String product, OrderSide side, long limit) throws SQLException {
        String opposite = side == OrderSide.BUY ? "SELL" : "BUY";
        String sort = side == OrderSide.BUY ? "unit_price_minor ASC" : "unit_price_minor DESC";
        String crossing = side == OrderSide.BUY ? "unit_price_minor<=?" : "unit_price_minor>=?";
        List<Resting> orders = new ArrayList<>();
        try (var query = c.prepareStatement("SELECT order_id,profile_uuid,unit_price_minor,remaining_quantity,created_at FROM bazaar_orders "
                + "WHERE product_id=? AND side=? AND status IN ('OPEN','PARTIAL') AND profile_uuid<>? AND " + crossing
                + " ORDER BY " + sort + ",created_at ASC,order_id ASC")) {
            query.setString(1, product); query.setString(2, opposite); query.setString(3, owner.toString()); query.setLong(4, limit);
            try (var rows = query.executeQuery()) {
                while (rows.next()) orders.add(new Resting(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)),
                        opposite.equals("BUY") ? OrderSide.BUY : OrderSide.SELL, rows.getLong(3), rows.getLong(4), rows.getLong(5)));
            }
        }
        return orders;
    }

    public List<BookLevel> book(String product, OrderSide side, int limit) throws SQLException {
        if (limit < 1 || limit > 54) throw new IllegalArgumentException("Invalid order book depth");
        try (Connection c = database.openConnection(); var query = c.prepareStatement("SELECT unit_price_minor,SUM(remaining_quantity),COUNT(*) FROM bazaar_orders WHERE product_id=? AND side=? AND status IN ('OPEN','PARTIAL') GROUP BY unit_price_minor ORDER BY unit_price_minor " + (side == OrderSide.BUY ? "DESC" : "ASC") + " LIMIT ?")) {
            query.setString(1, product); query.setString(2, side.name()); query.setInt(3, limit);
            try (var rows = query.executeQuery()) { List<BookLevel> result = new ArrayList<>(); while (rows.next()) result.add(new BookLevel(rows.getLong(1), rows.getLong(2), rows.getInt(3))); return List.copyOf(result); }
        }
    }

    public List<OrderView> myOrders(UUID owner, int limit) throws SQLException {
        if (limit < 1 || limit > 54) throw new IllegalArgumentException("Invalid order limit");
        try (Connection c = database.openConnection(); var query = c.prepareStatement("SELECT order_id,product_id,side,unit_price_minor,remaining_quantity,filled_quantity,status,created_at FROM bazaar_orders WHERE profile_uuid=? ORDER BY created_at DESC LIMIT ?")) {
            query.setString(1, owner.toString()); query.setInt(2, limit);
            try (var rows = query.executeQuery()) {
                List<OrderView> result = new ArrayList<>();
                while (rows.next()) result.add(new OrderView(UUID.fromString(rows.getString(1)), rows.getString(2), OrderSide.valueOf(rows.getString(3)), rows.getLong(4), rows.getLong(5), rows.getLong(6), rows.getString(7), rows.getLong(8)));
                return List.copyOf(result);
            }
        }
    }

    public List<ItemClaim> pendingItemClaims(UUID owner) throws SQLException {
        try (Connection c = database.openConnection(); var query = c.prepareStatement("SELECT claim_id,product_id,quantity FROM bazaar_claimables WHERE profile_uuid=? AND asset_type='ITEMS' AND claimed_at IS NULL ORDER BY created_at LIMIT 100")) {
            query.setString(1, owner.toString());
            try (var rows = query.executeQuery()) {
                List<ItemClaim> result = new ArrayList<>();
                while (rows.next()) result.add(new ItemClaim(rows.getString(1), rows.getString(2), rows.getLong(3)));
                return List.copyOf(result);
            }
        }
    }

    /** Split a large durable claim into a deliverable portion; both remain owner-scoped. */
    public ItemClaim claimPortion(UUID owner,String claimId,long requested,long now)throws SQLException {
        if(requested<1||requested>64)throw new IllegalArgumentException("Invalid claim portion");
        return database.transact(c->{
            String product;long quantity;
            try(var q=c.prepareStatement("SELECT product_id,quantity FROM bazaar_claimables WHERE claim_id=? AND profile_uuid=? AND asset_type='ITEMS' AND claimed_at IS NULL")) {
                q.setString(1,claimId);q.setString(2,owner.toString());try(var r=q.executeQuery()){if(!r.next())return null;product=r.getString(1);quantity=r.getLong(2);}
            }
            if(quantity<=requested)return new ItemClaim(claimId,product,quantity);
            String child=UUID.randomUUID().toString();
            try(var u=c.prepareStatement("UPDATE bazaar_claimables SET quantity=quantity-? WHERE claim_id=? AND quantity>=? AND claimed_at IS NULL")){
                u.setLong(1,requested);u.setString(2,claimId);u.setLong(3,requested);if(u.executeUpdate()!=1)throw new SQLException("Claim changed during split");
            }
            try(var insert=c.prepareStatement("INSERT INTO bazaar_claimables(claim_id,profile_uuid,asset_type,product_id,amount_minor,quantity,source_transaction_id,created_at) VALUES(?,?,'ITEMS',?,0,?,?,?)")){
                insert.setString(1,child);insert.setString(2,owner.toString());insert.setString(3,product);insert.setLong(4,requested);insert.setString(5,"split:"+child);insert.setLong(6,now);insert.executeUpdate();
            }
            return new ItemClaim(child,product,requested);
        });
    }

    /** Acknowledge only the actual legacy-tagged quantity; preserve an undelivered remainder. */
    public long reconcileLegacyDelivery(UUID owner, String claimId, String product, long original, long delivered, long now) throws SQLException {
        if (original < 1 || delivered < 1 || delivered > original) throw new IllegalArgumentException("Invalid legacy quantity");
        return database.transact(c -> {
            try (var receipt = c.prepareStatement("SELECT owner_uuid,product_id,original_quantity,delivered_quantity FROM legacy_claim_reconciliations WHERE operation_key=?")) {
                receipt.setString(1, claimId);
                try (var row = receipt.executeQuery()) {
                    if (row.next()) {
                        if (!owner.toString().equals(row.getString(1)) || !product.equals(row.getString(2)) || original != row.getLong(3) || delivered != row.getLong(4))
                            throw new SQLException("Legacy receipt replay does not match its original delivery");
                        return original - delivered;
                    }
                }
            }
            try (var update = c.prepareStatement("UPDATE bazaar_claimables SET quantity=?,claimed_at=? WHERE claim_id=? AND profile_uuid=? AND product_id=? AND quantity=? AND asset_type='ITEMS' AND claimed_at IS NULL")) {
                update.setLong(1, delivered == original ? original : original - delivered);
                if (delivered == original) update.setLong(2, now); else update.setNull(2, java.sql.Types.BIGINT);
                update.setString(3, claimId); update.setString(4, owner.toString()); update.setString(5, product); update.setLong(6, original);
                if (update.executeUpdate() != 1) throw new SQLException("Legacy claim changed or belongs to another owner");
            }
            try (var receipt = c.prepareStatement("INSERT INTO legacy_claim_reconciliations(operation_key,owner_uuid,claim_id,product_id,original_quantity,delivered_quantity,created_at) VALUES(?,?,?,?,?,?,?)")) {
                receipt.setString(1, claimId); receipt.setString(2, owner.toString()); receipt.setString(3, claimId); receipt.setString(4, product);
                receipt.setLong(5, original); receipt.setLong(6, delivered); receipt.setLong(7, now); receipt.executeUpdate();
            }
            return original - delivered;
        });
    }

    public void markClaimed(UUID owner, String claimId, long now) throws SQLException {
        database.transact(c -> {
            try (var update = c.prepareStatement("UPDATE bazaar_claimables SET claimed_at=? WHERE claim_id=? AND profile_uuid=? AND asset_type='ITEMS' AND claimed_at IS NULL")) {
                update.setLong(1, now); update.setString(2, claimId); update.setString(3, owner.toString());
                if (update.executeUpdate() != 1) throw new SQLException("Bazaar item claim is missing or already claimed");
            }
            return null;
        });
    }

    public PlaceResult cancel(UUID owner, UUID orderId, String operationKey, long now) throws SQLException {
        try {
            return database.transact(c -> {
                try (var q = c.prepareStatement("SELECT product_id,side,status,remaining_quantity FROM bazaar_orders WHERE order_id=? AND profile_uuid=?")) {
                    q.setString(1, orderId.toString()); q.setString(2, owner.toString());
                    try (var row = q.executeQuery()) {
                        if (!row.next()) throw reject(Outcome.NOT_OWNER, "Order not found for this profile");
                        String product = row.getString(1); OrderSide side = OrderSide.valueOf(row.getString(2));
                        if (row.getString(3).equals("CANCELLED")) return new PlaceResult(Outcome.ALREADY_APPLIED, orderId, 0, 0, "Already cancelled");
                        if (!row.getString(3).equals("OPEN") && !row.getString(3).equals("PARTIAL")) throw reject(Outcome.NOT_OPEN, "Order is not open");
                        long remaining = row.getLong(4);
                        long refundCoins = 0;
                        if (side == OrderSide.BUY) {
                            try (var escrow = c.prepareStatement("SELECT amount_minor FROM bazaar_escrow WHERE order_id=?")) {
                                escrow.setString(1, orderId.toString()); try (var er = escrow.executeQuery()) { if (er.next()) refundCoins = er.getLong(1); }
                            }
                            if (refundCoins > 0) {
                                if (database.updateBalance(c, owner, refundCoins) != MarketplaceDatabase.ApplyResult.APPLIED) throw new SQLException("Order refund failed");
                                database.ledger(c, owner, refundCoins, "Bazaar order cancellation refund", now, "bazaar:cancel:" + operationKey);
                            }
                        } else if (remaining > 0) {
                            try (var claim = c.prepareStatement("INSERT INTO bazaar_claimables(claim_id,profile_uuid,asset_type,product_id,amount_minor,quantity,source_transaction_id,created_at) VALUES(?,?,'ITEMS',?,0,?,?,?)")) {
                                claim.setString(1, UUID.randomUUID().toString()); claim.setString(2, owner.toString()); claim.setString(3, product);
                                claim.setLong(4, remaining); claim.setString(5, "cancel:" + operationKey); claim.setLong(6, now); claim.executeUpdate();
                            }
                        }
                        try (var update = c.prepareStatement("UPDATE bazaar_orders SET status='CANCELLED',updated_at=?,version=version+1 WHERE order_id=?")) { update.setLong(1, now); update.setString(2, orderId.toString()); update.executeUpdate(); }
                        try (var update = c.prepareStatement("UPDATE bazaar_escrow SET amount_minor=0,quantity=0,updated_at=?,version=version+1 WHERE order_id=?")) { update.setLong(1, now); update.setString(2, orderId.toString()); update.executeUpdate(); }
                        try (var tx = c.prepareStatement("INSERT INTO bazaar_transactions(transaction_id,idempotency_key,transaction_type,profile_uuid,order_id,amount_minor,quantity,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
                            tx.setString(1, "bazaar:cancel:" + operationKey); tx.setString(2, "cancel:" + operationKey); tx.setString(3, "CANCEL"); tx.setString(4, owner.toString());
                            tx.setString(5, orderId.toString()); tx.setLong(6, refundCoins); tx.setLong(7, remaining); tx.setLong(8, now); tx.executeUpdate();
                        }
                        return new PlaceResult(Outcome.CANCELLED, orderId, 0, 0, "Order cancelled");
                    }
                }
            });
        } catch (Rejected rejection) { return new PlaceResult(rejection.outcome, orderId, 0, 0, rejection.getMessage()); }
    }

    private static Rejected reject(Outcome outcome, String message) { return new Rejected(outcome, message); }
    private static final class Rejected extends RuntimeException {
        private final Outcome outcome;
        private Rejected(Outcome outcome, String message) { super(message); this.outcome = outcome; }
    }
    private record Resting(UUID id, UUID owner, OrderSide side, long price, long remaining, long createdAt) {}
}
