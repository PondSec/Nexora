package network.nexora.trading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class MarketplaceDatabaseTest {
    @TempDir Path temp;

    private Path database(long first, long second) throws Exception {
        Path file = temp.resolve(UUID.randomUUID() + ".sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("CREATE TABLE profiles(uuid TEXT PRIMARY KEY,balance_minor INTEGER NOT NULL CHECK(balance_minor>=0))");
            statement.execute("CREATE TABLE economy_ledger(id TEXT PRIMARY KEY,uuid TEXT NOT NULL,delta_minor INTEGER NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
            try (var insert = connection.prepareStatement("INSERT INTO profiles(uuid,balance_minor) VALUES(?,?)")) {
                insert.setString(1, id(1).toString()); insert.setLong(2, first); insert.executeUpdate();
                insert.setString(1, id(2).toString()); insert.setLong(2, second); insert.executeUpdate();
            }
        }
        return file;
    }

    private static UUID id(long n) { return new UUID(0, n); }

    @Test void legacyTagLookupRequiresDurableItemDeliveryAcrossAllMarketDomains() throws Exception {
        var db = new MarketplaceDatabase(database(100000, 100000));
        var bazaar = new BazaarOrderService(db);
        bazaar.registerProduct("coal", "minecraft:coal", 1);
        bazaar.place(id(1), "coal", OrderSide.SELL, 100, 1, false, "legacy-sell", 2);
        bazaar.place(id(2), "coal", OrderSide.BUY, 100, 1, false, "legacy-buy", 3);
        String commodity = bazaar.pendingItemClaims(id(2)).getFirst().claimId();
        var ah = new AuctionHouseService(db);
        var listing = ah.create(id(1), AuctionHouseService.Mode.BIN, 1000, 10000,
                "minecraft:diamond_sword", new byte[]{1,2}, "legacy-list", 4);
        ah.buyBin(id(2), listing.auctionId(), "legacy-ah-buy", 5);
        String equipment = ah.pendingClaims(id(2)).getFirst().claimId();
        var standard = new StandardizedBazaarService(db);
        standard.buy(id(2), "nexora:infinite_rocket", 1000, 1, "legacy-rocket-buy", 6);
        standard.sell(id(1), "nexora:infinite_rocket", 1000,
                List.of(new StandardizedBazaarService.StandardItem("legacy-instance", new byte[]{3,4})), "legacy-rocket-sell", 7);
        String rocket = standard.pendingClaims(id(2)).getFirst().claimId();
        var ids = java.util.Set.of(commodity, equipment, rocket, "unknown");
        assertTrue(db.deliveredItemClaims(ids).isEmpty(), "Pending deliveries must keep their recovery tags");
        bazaar.markClaimed(id(2), commodity, 8);
        ah.markClaimed(id(2), equipment, 9);
        standard.markClaimed(id(2), rocket, 10);
        assertEquals(java.util.Set.of(commodity, equipment, rocket), db.deliveredItemClaims(ids));
        assertTrue(db.deliveredItemClaims(java.util.Set.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> db.deliveredItemClaims(java.util.Set.of(" ")));
    }

    @Test void migrationIsIdempotentAndWalletChangesAreIdempotent() throws Exception {
        Path file = database(1_000, 500);
        var store = new MarketplaceDatabase(file);
        new MarketplaceDatabase(file);
        assertEquals(MarketplaceDatabase.ApplyResult.APPLIED,
                store.applyDelta(id(1), -300, "order-create", "Reserve market funds", 10));
        assertEquals(MarketplaceDatabase.ApplyResult.ALREADY_APPLIED,
                store.applyDelta(id(1), -300, "order-create", "Reserve market funds", 11));
        assertEquals(700, store.balanceMinor(id(1)));
        assertThrows(java.sql.SQLException.class,
                () -> store.applyDelta(id(1), -200, "order-create", "Reserve market funds", 12),
                "reusing an idempotency key for a different amount must be rejected");
        assertEquals(1, scalar(file, "SELECT count(*) FROM market_money_transactions"));
        assertEquals(1, scalar(file, "SELECT count(*) FROM economy_ledger"));
        assertEquals(0, scalar(file, "SELECT count(*) FROM pragma_foreign_key_check"));
    }

    @Test void transferIsAtomicAndNotAppliedTwice() throws Exception {
        Path file = database(1_000, 100);
        var store = new MarketplaceDatabase(file);
        assertEquals(MarketplaceDatabase.ApplyResult.APPLIED, store.transfer(id(1), id(2), 650, "fill-1", "Bazaar fill", 20));
        assertEquals(MarketplaceDatabase.ApplyResult.ALREADY_APPLIED, store.transfer(id(1), id(2), 650, "fill-1", "Bazaar fill", 21));
        assertEquals(350, store.balanceMinor(id(1)));
        assertEquals(750, store.balanceMinor(id(2)));
        assertThrows(java.sql.SQLException.class,
                () -> store.transfer(id(1), id(2), 100, "fill-1", "Bazaar fill", 22));
        assertEquals(2, scalar(file, "SELECT count(*) FROM economy_ledger"));
    }

    @Test void insufficientAndConcurrentDebitsNeverCreateNegativePurse() throws Exception {
        Path file = database(1_000, 0);
        var store = new MarketplaceDatabase(file);
        assertEquals(MarketplaceDatabase.ApplyResult.INSUFFICIENT_FUNDS,
                store.applyDelta(id(1), -1_001, "too-much", "Reserve", 30));
        assertEquals(0, scalar(file, "SELECT count(*) FROM market_money_transactions"),
                "failed debits must roll back their idempotency row as well");
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<MarketplaceDatabase.ApplyResult>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                final int index = i;
                futures.add(executor.submit(() -> store.applyDelta(id(1), -100, "concurrent-" + index, "Reserve", 40 + index)));
            }
            long applied = 0;
            for (var future : futures) if (future.get() == MarketplaceDatabase.ApplyResult.APPLIED) applied++;
            assertEquals(10, applied);
        }
        assertEquals(0, store.balanceMinor(id(1)));
        assertEquals(10, scalar(file, "SELECT count(*) FROM economy_ledger"));
    }

    @Test void oneThousandParallelDebitsCannotOverspend() throws Exception {
        Path file = database(1_000, 0);
        var store = new MarketplaceDatabase(file);
        try (var executor = Executors.newFixedThreadPool(32)) {
            List<java.util.concurrent.Future<MarketplaceDatabase.ApplyResult>> futures = new ArrayList<>();
            for (int index = 0; index < 1_000; index++) {
                final int transaction = index;
                futures.add(executor.submit(() -> store.applyDelta(id(1), -10,
                        "stress-debit-" + transaction, "Stress reserve", transaction)));
            }
            long applied = 0;
            long insufficient = 0;
            for (var future : futures) {
                var result = future.get();
                if (result == MarketplaceDatabase.ApplyResult.APPLIED) applied++;
                else if (result == MarketplaceDatabase.ApplyResult.INSUFFICIENT_FUNDS) insufficient++;
                else fail("Unexpected concurrent debit result: " + result);
            }
            assertEquals(100, applied);
            assertEquals(900, insufficient);
        }
        assertEquals(0, store.balanceMinor(id(1)));
        assertEquals(100, scalar(file, "SELECT count(*) FROM market_money_transactions"));
        assertEquals(100, scalar(file, "SELECT count(*) FROM economy_ledger"));
        assertEquals(100, scalar(file, "SELECT count(DISTINCT transaction_id) FROM market_money_transactions"));
    }

    @Test void concurrentRetriesOfSameCreditPayExactlyOnce() throws Exception {
        Path file = database(0, 0);
        var store = new MarketplaceDatabase(file);
        try (var executor = Executors.newFixedThreadPool(32)) {
            List<java.util.concurrent.Future<MarketplaceDatabase.ApplyResult>> futures = new ArrayList<>();
            for (int attempt = 0; attempt < 256; attempt++) {
                final int retry = attempt;
                futures.add(executor.submit(() -> store.applyDelta(id(1), 7_500,
                        "same-credit-retry", "Auction settlement", retry)));
            }
            long applied = 0;
            long duplicates = 0;
            for (var future : futures) {
                var result = future.get();
                if (result == MarketplaceDatabase.ApplyResult.APPLIED) applied++;
                else if (result == MarketplaceDatabase.ApplyResult.ALREADY_APPLIED) duplicates++;
                else fail("Unexpected credit retry result: " + result);
            }
            assertEquals(1, applied);
            assertEquals(255, duplicates);
        }
        assertEquals(7_500, store.balanceMinor(id(1)));
        assertEquals(1, scalar(file, "SELECT count(*) FROM market_money_transactions"));
        assertEquals(1, scalar(file, "SELECT count(*) FROM economy_ledger"));
    }

    private static long scalar(Path file, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }
}
