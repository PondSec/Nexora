package network.nexora.trading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BazaarOrderServiceTest {
    @TempDir Path temp;
    private static UUID id(long value) { return new UUID(0, value); }

    private MarketplaceDatabase store(long seller, long buyer) throws Exception {
        Path file = temp.resolve(UUID.randomUUID() + ".sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE profiles(uuid TEXT PRIMARY KEY,balance_minor INTEGER NOT NULL CHECK(balance_minor>=0))");
            statement.execute("CREATE TABLE economy_ledger(id TEXT PRIMARY KEY,uuid TEXT NOT NULL,delta_minor INTEGER NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
            try (var insert = connection.prepareStatement("INSERT INTO profiles(uuid,balance_minor) VALUES(?,?)")) {
                insert.setString(1, id(1).toString()); insert.setLong(2, seller); insert.executeUpdate();
                insert.setString(1, id(2).toString()); insert.setLong(2, buyer); insert.executeUpdate();
            }
        }
        return new MarketplaceDatabase(file);
    }

    @Test void matchesAgainstRestingSellAndRefundsPriceImprovement() throws Exception {
        var db = store(0, 100_000);
        var bazaar = new BazaarOrderService(db);
        assertTrue(bazaar.registerProduct("iron_ingot", "minecraft:iron_ingot", 1));
        var sell = bazaar.place(id(1), "iron_ingot", OrderSide.SELL, 100, 5, false, "sell-1", 2);
        assertEquals(BazaarOrderService.Outcome.OPENED, sell.outcome());
        var buy = bazaar.place(id(2), "iron_ingot", OrderSide.BUY, 120, 3, true, "buy-1", 3);
        assertEquals(BazaarOrderService.Outcome.FILLED, buy.outcome());
        assertEquals(3, buy.filled());
        assertEquals(0, buy.remaining());
        assertEquals(99_698, db.balanceMinor(id(2))); // 360 reserved, 60 price improvement, 2 fee.
        assertEquals(297, db.balanceMinor(id(1))); // 1% seller settlement fee.
        assertEquals(3, scalar(db, "SELECT quantity FROM bazaar_claimables WHERE profile_uuid='" + id(2) + "'"));
        assertEquals(2, bazaar.book("iron_ingot", OrderSide.SELL, 10).getFirst().quantity());
        assertEquals(0, scalar(db, "SELECT count(*) FROM pragma_foreign_key_check"));
    }

    @Test void cancellationReleasesOnlyRemainingCoinEscrow() throws Exception {
        var db = store(0, 100_000);
        var bazaar = new BazaarOrderService(db);
        bazaar.registerProduct("iron_ingot", "minecraft:iron_ingot", 1);
        bazaar.place(id(1), "iron_ingot", OrderSide.SELL, 100, 2, false, "sell-2", 2);
        var buy = bazaar.place(id(2), "iron_ingot", OrderSide.BUY, 120, 5, false, "buy-2", 3);
        assertEquals(2, buy.filled());
        assertEquals(3, buy.remaining());
        long afterPlace = db.balanceMinor(id(2));
        var cancelled = bazaar.cancel(id(2), buy.orderId(), "cancel-2", 4);
        assertEquals(BazaarOrderService.Outcome.CANCELLED, cancelled.outcome());
        assertEquals(afterPlace + 360, db.balanceMinor(id(2)));
        assertEquals(BazaarOrderService.Outcome.ALREADY_APPLIED,
                bazaar.cancel(id(2), buy.orderId(), "cancel-retry", 5).outcome());
        assertEquals(0, scalar(db, "SELECT amount_minor FROM bazaar_escrow WHERE order_id='" + buy.orderId() + "'"));
    }

    @Test void rejectedFullFillRollsBackOrderAndEscrow() throws Exception {
        var db = store(0, 1_000);
        var bazaar = new BazaarOrderService(db);
        bazaar.registerProduct("iron_ingot", "minecraft:iron_ingot", 1);
        var result = bazaar.place(id(2), "iron_ingot", OrderSide.BUY, 100, 20, true, "no-liquidity", 9);
        assertEquals(BazaarOrderService.Outcome.INSUFFICIENT_LIQUIDITY, result.outcome());
        assertEquals(1_000, db.balanceMinor(id(2)));
        assertEquals(0, scalar(db, "SELECT count(*) FROM bazaar_orders"));
        assertEquals(0, scalar(db, "SELECT count(*) FROM bazaar_transactions"));
        assertEquals(0, scalar(db, "SELECT count(*) FROM economy_ledger"));
    }

    private static long scalar(MarketplaceDatabase db, String sql) throws Exception {
        try (var c = db.openConnection(); var s = c.createStatement(); var rs = s.executeQuery(sql)) {
            assertTrue(rs.next()); return rs.getLong(1);
        }
    }
}
