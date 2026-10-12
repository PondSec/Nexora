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

    @Test void deliveredRocketCanBeResoldRepeatedlyWithoutLosingHistoricalClaims() throws Exception {
        var db=db(100_000,100_000);var bazaar=new StandardizedBazaarService(db);
        var item=new StandardizedBazaarService.StandardItem("resell-instance",new byte[]{1,2,3});
        for(int cycle=0;cycle<3;cycle++) {
            UUID seller=id(cycle%2+1),buyer=id((cycle+1)%2+1);
            var sell=bazaar.sell(seller,"nexora:infinite_rocket",100,List.of(item),"resell-"+cycle,cycle*10);
            assertEquals(StandardizedBazaarService.Outcome.OPENED,sell.outcome());
            assertEquals(StandardizedBazaarService.Outcome.FILLED,bazaar.buy(buyer,"nexora:infinite_rocket",100,1,"rebuy-"+cycle,cycle*10+1).outcome());
            var claim=bazaar.pendingClaims(buyer).getFirst();
            assertThrows(java.sql.SQLException.class,()->bazaar.markClaimed(seller,claim.claimId(),999));
            bazaar.markClaimed(buyer,claim.claimId(),cycle*10+2);
        }
        assertEquals(3,scalar(db,"SELECT count(*) FROM standardized_bazaar_claims"));
        assertEquals(3,scalar(db,"SELECT count(*) FROM standardized_bazaar_order_items"));
        assertEquals(0,scalar(db,"SELECT count(*) FROM market_item_reservations"));
    }

    @Test void requestReplayDoesNotDebitOrCreateAnotherOrderAndRejectsDifferentPayload() throws Exception {
        var db=db(100_000,100_000);var bazaar=new StandardizedBazaarService(db);
        var first=bazaar.buy(id(1),"nexora:infinite_rocket",1000,2,"repeat-buy",10);
        assertEquals(first,new StandardizedBazaarService(db).buy(id(1),"nexora:infinite_rocket",1000,2,"repeat-buy",20));
        assertEquals(98_000,db.balanceMinor(id(1)));
        assertEquals(1,scalar(db,"SELECT count(*) FROM standardized_bazaar_orders"));
        assertThrows(IllegalArgumentException.class,()->bazaar.buy(id(1),"nexora:infinite_rocket",1001,2,"repeat-buy",30));
        assertThrows(IllegalArgumentException.class,()->bazaar.buy(id(2),"nexora:infinite_rocket",1000,2,"repeat-buy",30));
        var item=new StandardizedBazaarService.StandardItem("replay-sell",new byte[]{9});
        var sell=bazaar.sell(id(2),"nexora:infinite_rocket",500,List.of(item),"repeat-sell",40);
        assertEquals(sell,bazaar.sell(id(2),"nexora:infinite_rocket",500,List.of(item),"repeat-sell",50));
        assertEquals(100_990,db.balanceMinor(id(2)));
    }

    @Test void migrationPreservesExistingPendingSnapshotsAndAllowsResaleAfterDelivery() throws Exception {
        Path file=temp.resolve("legacy-v4.sqlite");String instance="legacy-rocket",order=UUID.randomUUID().toString(),claim=UUID.randomUUID().toString();
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file);var statement=c.createStatement()) {
            statement.execute("CREATE TABLE profiles(uuid TEXT PRIMARY KEY,balance_minor INTEGER NOT NULL)");
            statement.execute("CREATE TABLE economy_ledger(id TEXT PRIMARY KEY,uuid TEXT NOT NULL,delta_minor INTEGER NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO profiles VALUES('"+id(1)+"',10000)");
            for(String resource:List.of("V1__market.sql","V2__bank.sql","V3__market_maker.sql","V4__item_instance_reservations.sql")) {
                try(var input=getClass().getResourceAsStream("/db/migration/"+resource)) {
                    for(String command:new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split(";"))if(!command.isBlank())statement.execute(command);
                }
            }
            statement.execute("INSERT INTO standardized_bazaar_orders VALUES('"+order+"','"+id(1)+"','nexora:infinite_rocket','SELL',100,1,1,0,0,'CANCELLED',1,2)");
            statement.execute("INSERT INTO standardized_bazaar_order_items VALUES('"+instance+"','"+order+"',X'010203','hash','CLAIMABLE','"+claim+"',2)");
            statement.execute("INSERT INTO standardized_bazaar_claims VALUES('"+claim+"','"+id(1)+"','"+order+"','"+instance+"',X'010203','legacy-source',NULL,2)");
            statement.execute("INSERT INTO market_item_reservations VALUES('"+instance+"','BAZAAR','"+claim+"','"+id(1)+"','CLAIMABLE',1,2)");
        }
        var db=new MarketplaceDatabase(file);var service=new StandardizedBazaarService(db);
        assertArrayEquals(new byte[]{1,2,3},service.pendingClaims(id(1)).getFirst().snapshot());
        service.markClaimed(id(1),claim,3);
        assertEquals(StandardizedBazaarService.Outcome.OPENED,service.sell(id(1),"nexora:infinite_rocket",100,List.of(new StandardizedBazaarService.StandardItem(instance,new byte[]{1,2,3})),"after-migration",4).outcome());
        assertEquals(2,scalar(new MarketplaceDatabase(file),"SELECT count(*) FROM standardized_bazaar_order_items"));
        assertEquals(1,scalar(db,"SELECT count(*) FROM standardized_bazaar_claims"));
        assertEquals("ok",scalarText(db,"PRAGMA integrity_check"));
    }

    private static long scalar(MarketplaceDatabase db,String sql)throws Exception{try(var c=db.openConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    private static String scalarText(MarketplaceDatabase db,String sql)throws Exception{try(var c=db.openConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
}
