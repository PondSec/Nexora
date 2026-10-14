package network.nexora.trading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuctionHouseServiceTest {
    @TempDir Path temp;
    private static UUID id(long n) { return new UUID(0, n); }

    private MarketplaceDatabase db(long... balances) throws Exception {
        Path file = temp.resolve(UUID.randomUUID() + ".sqlite");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var s = c.createStatement()) {
            s.execute("CREATE TABLE profiles(uuid TEXT PRIMARY KEY,balance_minor INTEGER NOT NULL CHECK(balance_minor>=0))");
            s.execute("CREATE TABLE economy_ledger(id TEXT PRIMARY KEY,uuid TEXT NOT NULL,delta_minor INTEGER NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
            try (var insert = c.prepareStatement("INSERT INTO profiles(uuid,balance_minor) VALUES(?,?)")) {
                for (int i = 0; i < balances.length; i++) { insert.setString(1, id(i + 1).toString()); insert.setLong(2, balances[i]); insert.executeUpdate(); }
            }
        }
        return new MarketplaceDatabase(file);
    }

    @Test void binPurchasePaysSellerAndQueuesRealItemSnapshotOnce() throws Exception {
        var db = db(500, 100_000);
        var ah = new AuctionHouseService(db);
        byte[] snapshot = new byte[]{1, 2, 3, 4};
        var listing = ah.create(id(1), AuctionHouseService.Mode.BIN, 10_000, 86_400_000,
                "minecraft:diamond_sword", snapshot, "listing-bin", 100);
        assertEquals(AuctionHouseService.Outcome.CREATED, listing.outcome());
        assertEquals(300, db.balanceMinor(id(1))); // 2% listing fee.
        assertEquals(AuctionHouseService.Outcome.PURCHASED,
                ah.buyBin(id(2), listing.auctionId(), "buy-bin", 200).outcome());
        assertEquals(10_100, db.balanceMinor(id(1))); // seller receives 98% after 2% fee.
        assertEquals(89_900, db.balanceMinor(id(2))); // 10,000 + 1% buyer fee.
        var claims = ah.pendingClaims(id(2));
        assertEquals(1, claims.size());
        assertArrayEquals(snapshot, claims.getFirst().snapshot());
        assertEquals(0, scalar(db, "SELECT count(*) FROM pragma_foreign_key_check"));
    }

    @Test void bidOutbidsRefundEscrowAndExpirySettlesOnlyLeadingBid() throws Exception {
        var db = db(500, 50_000, 50_000);
        var ah = new AuctionHouseService(db);
        var listing = ah.create(id(1), AuctionHouseService.Mode.BID, 10_000, 1_000,
                "minecraft:enchanted_book", new byte[]{9}, "listing-bid", 0);
        assertEquals(AuctionHouseService.Outcome.BID_PLACED, ah.bid(id(2), listing.auctionId(), 10_000, "bid-one", 1).outcome());
        assertEquals(AuctionHouseService.Outcome.BID_PLACED, ah.bid(id(3), listing.auctionId(), 11_000, "bid-two", 2).outcome());
        assertEquals(50_000, db.balanceMinor(id(2)), "outbid bidder receives their escrow back");
        assertEquals(39_000, db.balanceMinor(id(3)));
        assertEquals(1, ah.settleExpired(1_001));
        assertEquals(11_080, db.balanceMinor(id(1))); // 11,000 bid less 2% seller fee, less 200 listing fee.
        assertEquals(1, ah.pendingClaims(id(3)).size());
        assertEquals(0, ah.pendingClaims(id(2)).size());
        assertEquals(1, scalar(db, "SELECT count(*) FROM auction_history WHERE result='SOLD'"));
    }

    @Test void sellerCanCancelOnlyUnbidListingAndReclaimItemSnapshot() throws Exception {
        var db = db(5_000, 50_000);
        var ah = new AuctionHouseService(db);
        byte[] snapshot = new byte[]{4, 2, 1};
        var listing = ah.create(id(1), AuctionHouseService.Mode.BIN, 10_000, 86_400_000,
                "minecraft:diamond", snapshot, "cancel-listing", 100);
        assertEquals(AuctionHouseService.Outcome.CANCELLED,
                ah.cancel(id(1), listing.auctionId(), "cancel-action", 200).outcome());
        assertEquals(1, ah.pendingClaims(id(1)).size());
        assertArrayEquals(snapshot, ah.pendingClaims(id(1)).getFirst().snapshot());
        assertEquals(AuctionHouseService.Outcome.NOT_ACTIVE,
                ah.cancel(id(1), listing.auctionId(), "cancel-again", 300).outcome());

        var bidListing = ah.create(id(1), AuctionHouseService.Mode.BID, 1_000, 86_400_000,
                "minecraft:diamond", snapshot, "cannot-cancel-bid", 400);
        assertEquals(AuctionHouseService.Outcome.BID_PLACED,
                ah.bid(id(2), bidListing.auctionId(), 1_000, "bid-before-cancel", 500).outcome());
        assertEquals(AuctionHouseService.Outcome.INVALID,
                ah.cancel(id(1), bidListing.auctionId(), "cancel-with-bid", 600).outcome());
        assertEquals(0, scalar(db, "SELECT count(*) FROM auction_claimables WHERE auction_id='" + bidListing.auctionId() + "'"));
    }

    @Test void uniqueItemInstanceCannotBeListedTwiceAndReservationEndsAfterClaim() throws Exception {
        var db = db(20_000, 50_000);
        var ah = new AuctionHouseService(db);
        byte[] snapshot = new byte[]{7, 8, 9};
        String instance = UUID.randomUUID().toString();
        var first = ah.create(id(1), AuctionHouseService.Mode.BIN, 1_000, 86_400_000,
                "nexora:infinite_rocket", snapshot, instance, "unique-first", 100);
        assertEquals(AuctionHouseService.Outcome.CREATED, first.outcome());
        var duplicate = ah.create(id(1), AuctionHouseService.Mode.BIN, 2_000, 86_400_000,
                "nexora:infinite_rocket", snapshot, instance, "unique-second", 101);
        assertEquals(AuctionHouseService.Outcome.INVALID, duplicate.outcome());
        assertEquals(1, scalar(db, "SELECT count(*) FROM auctions"));
        assertEquals(AuctionHouseService.Outcome.CANCELLED,
                ah.cancel(id(1), first.auctionId(), "unique-cancel", 200).outcome());
        var claim = ah.pendingClaims(id(1)).getFirst();
        assertArrayEquals(snapshot, claim.snapshot());
        ah.markClaimed(id(1), claim.claimId(), 201);
        assertEquals(0, scalar(db, "SELECT count(*) FROM market_item_reservations"));
        assertEquals(AuctionHouseService.Outcome.CREATED,
                ah.create(id(1), AuctionHouseService.Mode.BIN, 2_000, 86_400_000,
                        "nexora:infinite_rocket", snapshot, instance, "unique-after-claim", 202).outcome());
    }

    private static long scalar(MarketplaceDatabase db, String sql) throws Exception {
        try (var c = db.openConnection(); var s = c.createStatement(); var rows = s.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
}
