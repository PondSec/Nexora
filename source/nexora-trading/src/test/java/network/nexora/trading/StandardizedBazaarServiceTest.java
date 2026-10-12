package network.nexora.trading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class StandardizedBazaarServiceTest {
    @TempDir Path temp;
    private static UUID id(long value) { return new UUID(0, value); }
    private MarketplaceDatabase db(long... balances) throws Exception {
        Path file = temp.resolve(UUID.randomUUID() + ".sqlite");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var s = c.createStatement()) {
            s.execute("CREATE TABLE profiles(uuid TEXT PRIMARY KEY,balance_minor INTEGER NOT NULL CHECK(balance_minor>=0))");
            s.execute("CREATE TABLE economy_ledger(id TEXT PRIMARY KEY,uuid TEXT NOT NULL,delta_minor INTEGER NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
            try (var i = c.prepareStatement("INSERT INTO profiles(uuid,balance_minor) VALUES(?,?)")) {
                for (int n = 0; n < balances.length; n++) { i.setString(1, id(n + 1).toString()); i.setLong(2, balances[n]); i.executeUpdate(); }
            }
        }
        return new MarketplaceDatabase(file);
    }

    @Test void matchingMovesExactNonStackableSnapshotAndReservationToClaim() throws Exception {
        var db = db(0, 1_000_000);
        var bazaar = new StandardizedBazaarService(db);
        var buy = bazaar.buy(id(2), "nexora:infinite_rocket", 100_000, 1, "buy-rocket", 100);
        assertEquals(StandardizedBazaarService.Outcome.OPENED, buy.outcome());
        var rocket = new StandardizedBazaarService.StandardItem(UUID.randomUUID().toString(), new byte[]{3, 1, 4, 1, 5});
        var sell = bazaar.sell(id(1), "nexora:infinite_rocket", 90_000, List.of(rocket), "sell-rocket", 200);
        assertEquals(StandardizedBazaarService.Outcome.FILLED, sell.outcome());
        assertEquals(99_000, db.balanceMinor(id(1)), "seller receives execution price less the 1% sink fee");
        assertEquals(900_000, db.balanceMinor(id(2)), "buyer pays the resting buy-order price");
        var claims = bazaar.pendingClaims(id(2));
        assertEquals(1, claims.size());
        assertEquals(rocket.instanceId(), claims.getFirst().instanceId());
        assertArrayEquals(rocket.snapshot(), claims.getFirst().snapshot());
        assertEquals("CLAIMABLE", scalarText(db, "SELECT state FROM market_item_reservations WHERE instance_id='" + rocket.instanceId() + "'"));
        bazaar.markClaimed(id(2), claims.getFirst().claimId(), 300);
        assertEquals(0, scalar(db, "SELECT count(*) FROM market_item_reservations"));
        assertEquals(0, scalar(db, "SELECT count(*) FROM standardized_bazaar_claims WHERE claimed_at IS NULL"));
    }

    @Test void duplicateInstanceAndCancelledSellAreSafe() throws Exception {
        var db = db(100_000, 100_000);
        var bazaar = new StandardizedBazaarService(db);
        var item = new StandardizedBazaarService.StandardItem("same-instance", new byte[]{8, 6, 7});
        var order = bazaar.sell(id(1), "nexora:infinite_rocket", 5_000, List.of(item), "sell-once", 100);
        assertEquals(StandardizedBazaarService.Outcome.OPENED, order.outcome());
        assertEquals(StandardizedBazaarService.Outcome.INVALID,
                bazaar.sell(id(1), "nexora:infinite_rocket", 6_000, List.of(item), "sell-twice", 101).outcome());
        assertEquals(StandardizedBazaarService.Outcome.CANCELLED, bazaar.cancel(id(1), order.orderId(), 200).outcome());
        var claim = bazaar.pendingClaims(id(1)).getFirst();
        assertArrayEquals(item.snapshot(), claim.snapshot());
        bazaar.markClaimed(id(1), claim.claimId(), 201);
        assertEquals(0, scalar(db, "SELECT count(*) FROM market_item_reservations"));
    }

    @Test void concurrentSellersCannotReserveTheSamePhysicalRocketTwice() throws Exception {
        var db = db(100_000, 100_000);
        var bazaar = new StandardizedBazaarService(db);
        var item = new StandardizedBazaarService.StandardItem("one-physical-rocket", new byte[]{2, 7, 1, 8});
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> bazaar.sell(id(1), "nexora:infinite_rocket", 500, List.of(item), "race-a", 1));
            var b = workers.submit(() -> bazaar.sell(id(2), "nexora:infinite_rocket", 500, List.of(item), "race-b", 2));
            var first = a.get(); var second = b.get();
            assertEquals(1, List.of(first, second).stream().filter(x -> x.outcome() == StandardizedBazaarService.Outcome.OPENED).count());
            assertEquals(1, List.of(first, second).stream().filter(x -> x.outcome() == StandardizedBazaarService.Outcome.INVALID).count());
        }
        assertEquals(1, scalar(db, "SELECT count(*) FROM market_item_reservations"));
        assertEquals(1, scalar(db, "SELECT count(*) FROM standardized_bazaar_order_items"));
    }

    @Test void instantOrdersFailCleanlyWhenTheEntireQuantityCannotFill() throws Exception {
        var db = db(50_000, 50_000);
        var bazaar = new StandardizedBazaarService(db);
        assertEquals(StandardizedBazaarService.Outcome.INSUFFICIENT_LIQUIDITY,
                bazaar.buyInstant(id(1), "nexora:infinite_rocket", 10_000, 1, "empty-instant-buy", 1).outcome());
        var item = new StandardizedBazaarService.StandardItem("unsold-rocket", new byte[]{6, 2, 6});
        assertEquals(StandardizedBazaarService.Outcome.INSUFFICIENT_LIQUIDITY,
                bazaar.sellInstant(id(1), "nexora:infinite_rocket", 10_000, List.of(item), "empty-instant-sell", 2).outcome());
        assertEquals(50_000, db.balanceMinor(id(1)));
        assertEquals(0, scalar(db, "SELECT count(*) FROM standardized_bazaar_orders"));
        assertEquals(0, scalar(db, "SELECT count(*) FROM market_item_reservations"));
    }

    @Test void rocketCannotBeCrossListedBetweenAuctionAndBazaar() throws Exception {
        var db = db(20_000, 20_000);
        var bazaar = new StandardizedBazaarService(db);
        var ah = new AuctionHouseService(db);
        String instance = UUID.randomUUID().toString();
        byte[] snapshot = new byte[]{4, 4, 4};
        var auction = ah.create(id(1), AuctionHouseService.Mode.BIN, 2_000, 86_400_000,
                "nexora:infinite_rocket", snapshot, instance, "cross-market-ah", 10);
        assertEquals(AuctionHouseService.Outcome.CREATED, auction.outcome());
        assertEquals(StandardizedBazaarService.Outcome.INVALID,
                bazaar.sell(id(1), "nexora:infinite_rocket", 1_500,
                        List.of(new StandardizedBazaarService.StandardItem(instance, snapshot)), "cross-market-bazaar", 11).outcome());
        assertEquals(AuctionHouseService.Outcome.CANCELLED, ah.cancel(id(1), auction.auctionId(), "cross-market-cancel", 12).outcome());
        var claim = ah.pendingClaims(id(1)).getFirst(); ah.markClaimed(id(1), claim.claimId(), 13);
        assertEquals(StandardizedBazaarService.Outcome.OPENED,
                bazaar.sell(id(1), "nexora:infinite_rocket", 1_500,
                        List.of(new StandardizedBazaarService.StandardItem(instance, snapshot)), "cross-market-after-claim", 14).outcome());
    }

    @Test void billionCoinClassPricesRemainExactInLongMinorUnits() throws Exception {
        var db = db(8_000_000_000_000L);
        var bazaar = new StandardizedBazaarService(db);
        long priceMinor = 5_000_000_000_000L;
        var result = bazaar.buy(id(1), "nexora:infinite_rocket", priceMinor, 1, "high-price", 1);
        assertEquals(StandardizedBazaarService.Outcome.OPENED, result.outcome());
        assertEquals(3_000_000_000_000L, db.balanceMinor(id(1)));
        assertEquals(priceMinor, scalar(db, "SELECT unit_price_minor FROM standardized_bazaar_orders WHERE order_id='" + result.orderId() + "'"));
    }

    private static long scalar(MarketplaceDatabase db,String sql)throws Exception{try(var c=db.openConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    private static String scalarText(MarketplaceDatabase db,String sql)throws Exception{try(var c=db.openConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
}
