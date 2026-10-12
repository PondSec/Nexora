package network.nexora.trading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcLiquidityServiceTest {
    @TempDir Path temp;
    private static UUID id(long n) { return new UUID(0, n); }
    private MarketplaceDatabase db() throws Exception {
        Path file = temp.resolve("npc.sqlite");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var s = c.createStatement()) {
            s.execute("CREATE TABLE profiles(uuid TEXT PRIMARY KEY,balance_minor INTEGER NOT NULL CHECK(balance_minor>=0))");
            s.execute("CREATE TABLE economy_ledger(id TEXT PRIMARY KEY,uuid TEXT NOT NULL,delta_minor INTEGER NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
            try (var insert = c.prepareStatement("INSERT INTO profiles(uuid,balance_minor) VALUES(?,?)")) { insert.setString(1,id(1).toString()); insert.setLong(2,100_000); insert.executeUpdate(); }
        }
        return new MarketplaceDatabase(file);
    }

    @Test void fundedSystemQuotesTradeAgainstFinitePersistentInventoryAndBudget() throws Exception {
        var db = db(); var bazaar = new BazaarOrderService(db); var maker = new NpcLiquidityService(db);
        bazaar.registerProduct("iron_ingot", "minecraft:iron_ingot", 1);
        var policy = new NpcLiquidityService.Policy("bazaar_master", "iron_ingot", 10_000, 1_000,
                10, 50_000, 20, 60_000, 2, 5_000);
        maker.configure(policy, 100);
        var quote = maker.quote(policy, 101);
        assertTrue(quote.systemOffer());
        assertEquals(9_500, quote.bidMinor());
        assertEquals(10_500, quote.askMinor());
        assertEquals(NpcLiquidityService.Outcome.TRADED, maker.sellToNpc(policy, id(1), 2, 9_500, "npc-sell", 102));
        assertEquals(118_810, db.balanceMinor(id(1)));
        assertEquals(2, maker.quote(policy, 103).askQuantity(), "available asks must respect remaining Coin budget");
        assertEquals(NpcLiquidityService.Outcome.TRADED, maker.buyFromNpc(policy, id(1), 1, 10_500, "npc-buy", 104));
        assertEquals(108_205, db.balanceMinor(id(1)));
        assertEquals(1, scalar(db, "SELECT quantity FROM bazaar_claimables WHERE profile_uuid='" + id(1) + "'"));
        assertEquals(50_000 - 19_000 + 10_500, scalar(db, "SELECT coin_budget_minor FROM bazaar_market_maker_accounts WHERE npc_id='bazaar_master'"));
        assertEquals(0, scalar(db, "SELECT count(*) FROM pragma_foreign_key_check"));
    }

    @Test void repeatedOperationAndExhaustedBudgetsCannotMintOrDuplicate() throws Exception {
        var db = db(); var bazaar = new BazaarOrderService(db); var maker = new NpcLiquidityService(db);
        bazaar.registerProduct("iron_ingot", "minecraft:iron_ingot", 1);
        var policy = new NpcLiquidityService.Policy("bazaar_master", "iron_ingot", 10_000, 1_000,
                0, 9_000, 0, 9_000, 0, 0);
        maker.configure(policy, 100);
        assertEquals(NpcLiquidityService.Outcome.INSUFFICIENT_LIQUIDITY, maker.sellToNpc(policy, id(1), 1, 9_500, "empty-npc", 101));
        assertEquals(100_000, db.balanceMinor(id(1)));
        assertEquals(0, scalar(db, "SELECT count(*) FROM bazaar_market_maker_transactions"));
    }

    @Test void batchedDisplayQuotesMatchAuthoritativeSinglesAndDoNotCreateExtraLiquidity() throws Exception {
        var db = db(); var bazaar = new BazaarOrderService(db); var maker = new NpcLiquidityService(db);
        bazaar.registerProduct("iron_ingot", "minecraft:iron_ingot", 1);
        bazaar.registerProduct("coal", "minecraft:coal", 1);
        var iron = new NpcLiquidityService.Policy("bazaar_master", "iron_ingot", 100, 1000, 10, 5000, 20, 6000, 2, 500);
        var coal = new NpcLiquidityService.Policy("bazaar_master", "coal", 10, 1000, 20, 1000, 30, 2000, 2, 100);
        maker.configure(iron, 100); maker.configure(coal, 100);
        maker.sellToNpc(iron, id(1), 2, 95, "batch-sale", 101);
        var quotes = maker.quotes(java.util.List.of(iron, coal), 102);
        assertEquals(maker.quote(iron, 102), quotes.get("iron_ingot"));
        assertEquals(maker.quote(coal, 102), quotes.get("coal"));
        assertThrows(UnsupportedOperationException.class, () -> quotes.clear());
        assertTrue(maker.quotes(java.util.List.of(), 102).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> maker.quotes(java.util.List.of(iron, iron), 103));
        assertEquals(1, scalar(db, "SELECT count(*) FROM bazaar_market_maker_transactions"));
        assertEquals(32, scalar(db, "SELECT SUM(inventory) FROM bazaar_market_maker_accounts"));
        assertEquals(0, scalar(db, "SELECT count(*) FROM pragma_foreign_key_check"));
    }

    private static long scalar(MarketplaceDatabase db, String sql) throws Exception {
        try (var c = db.openConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getLong(1); }
    }
}
