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

class BankAccountTest {
    private static final long DAY = 86_400_000L;
    @TempDir Path temp;
    private Path activeDb;

    private Path database(long purse) throws Exception {
        Path file = temp.resolve(UUID.randomUUID() + ".sqlite");
        activeDb = file;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE profiles(uuid TEXT PRIMARY KEY,balance_minor INTEGER NOT NULL CHECK(balance_minor>=0))");
            statement.execute("CREATE TABLE economy_ledger(id TEXT PRIMARY KEY,uuid TEXT NOT NULL,delta_minor INTEGER NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
            try (var insert = connection.prepareStatement("INSERT INTO profiles(uuid,balance_minor) VALUES(?,?)")) {
                insert.setString(1, id().toString()); insert.setLong(2, purse); insert.executeUpdate();
            }
        }
        return file;
    }

    private static UUID id() { return new UUID(0, 501); }

    @Test void depositAndWithdrawalMoveCoinsAtomicallyAndAreIdempotent() throws Exception {
        var store = new MarketplaceDatabase(database(10_000));
        assertEquals(MarketplaceDatabase.BankResult.APPLIED, store.deposit(id(), 4_000, "deposit-1", 1_000));
        assertEquals(MarketplaceDatabase.BankResult.ALREADY_APPLIED, store.deposit(id(), 4_000, "deposit-1", 2_000));
        assertThrows(java.sql.SQLException.class, () -> store.deposit(id(), 3_000, "deposit-1", 2_100));
        assertEquals(6_000, store.balanceMinor(id()));
        assertEquals(4_000, store.bankBalanceMinor(id()));
        assertEquals(MarketplaceDatabase.BankResult.INSUFFICIENT_BANK, store.withdraw(id(), 4_001, "withdraw-too-much", 3_000));
        assertEquals(4_000, store.bankBalanceMinor(id()), "failed withdrawal rolls back its operation row");
        assertEquals(MarketplaceDatabase.BankResult.APPLIED, store.withdraw(id(), 2_500, "withdraw-1", 4_000));
        assertEquals(8_500, store.balanceMinor(id()));
        assertEquals(1_500, store.bankBalanceMinor(id()));
    }

    @Test void interestStartsAfterFullDayAndCompoundsOncePerElapsedDay() throws Exception {
        var store = new MarketplaceDatabase(database(100_000));
        long start = 50_000;
        assertEquals(MarketplaceDatabase.BankResult.APPLIED, store.deposit(id(), 50_000, "seed", start));
        assertEquals(0, store.accrueDailyInterest(id(), start + DAY - 1));
        assertEquals(1_000, store.accrueDailyInterest(id(), start + DAY));
        assertEquals(0, store.accrueDailyInterest(id(), start + DAY));
        assertEquals(1_020, store.accrueDailyInterest(id(), start + DAY * 2));
        assertEquals(52_020, store.bankBalanceMinor(id()));
        assertEquals(2, scalar("SELECT count(*) FROM bank_transactions WHERE type='INTEREST'"));
    }

    @Test void emptyAccountTimeDoesNotGiveNewDepositsEarlyInterest() throws Exception {
        var store = new MarketplaceDatabase(database(100_000));
        long opened = 20_000;
        assertEquals(0, store.accrueDailyInterest(id(), opened));
        long deposited = opened + DAY - 1;
        assertEquals(MarketplaceDatabase.BankResult.APPLIED, store.deposit(id(), 10_000, "late-deposit", deposited));
        assertEquals(0, store.accrueDailyInterest(id(), opened + DAY));
        assertEquals(200, store.accrueDailyInterest(id(), deposited + DAY));
        assertEquals(10_200, store.bankBalanceMinor(id()));
    }

    @Test void parallelInterestClaimsPayExactlyOnce() throws Exception {
        var store = new MarketplaceDatabase(database(10_000));
        long start = 100_000;
        store.deposit(id(), 10_000, "seed", start);
        try (var executor = Executors.newFixedThreadPool(24)) {
            List<java.util.concurrent.Future<Long>> claims = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                claims.add(executor.submit(() -> store.accrueDailyInterest(id(), start + DAY)));
            }
            long totalEarned = 0;
            for (var claim : claims) totalEarned += claim.get();
            assertEquals(200, totalEarned);
        }
        assertEquals(10_200, store.bankBalanceMinor(id()));
        assertEquals(1, scalar("SELECT count(*) FROM bank_transactions WHERE type='INTEREST'"));
    }

    private long scalar(String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + activeDb);
             var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }
}
