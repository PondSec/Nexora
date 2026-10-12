package network.nexora.trading;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;

/** SQLite adapter over NexoraCore's existing profile and wallet tables. */
public final class MarketplaceDatabase {
    public enum ApplyResult { APPLIED, ALREADY_APPLIED, INSUFFICIENT_FUNDS, UNKNOWN_PROFILE, BALANCE_LIMIT }
    public enum BankResult { APPLIED, ALREADY_APPLIED, INSUFFICIENT_PURSE, INSUFFICIENT_BANK, UNKNOWN_PROFILE, BALANCE_LIMIT }
    private static final long INTEREST_PERIOD_MILLIS = 86_400_000L;
    private static final int BANK_INTEREST_BPS = 200;

    private final String jdbcUrl;

    public MarketplaceDatabase(Path nexoraCoreDatabase) throws SQLException {
        Objects.requireNonNull(nexoraCoreDatabase);
        jdbcUrl = "jdbc:sqlite:" + nexoraCoreDatabase.toAbsolutePath();
        try (Connection connection = connect()) {
            if (!tableExists(connection, "profiles") || !tableExists(connection, "economy_ledger"))
                throw new SQLException("The connected database is not a NexoraCore wallet database");
            try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA journal_mode=WAL"); }
            runMigration(connection);
        }
    }

    public long balanceMinor(UUID profileId) throws SQLException {
        try (Connection connection = connect(); var query = connection.prepareStatement(
                "SELECT balance_minor FROM profiles WHERE uuid=?")) {
            query.setString(1, profileId.toString());
            try (var row = query.executeQuery()) { return row.next() ? row.getLong(1) : -1; }
        }
    }

    public long bankBalanceMinor(UUID profileId) throws SQLException {
        try (Connection connection = connect(); var query = connection.prepareStatement(
                "SELECT balance_minor FROM bank_accounts WHERE profile_uuid=?")) {
            query.setString(1, profileId.toString());
            try (var row = query.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
        }
    }

    /** Unique standardized items stay unavailable until their escrow claim has been durably delivered. */
    public java.util.Set<String> reservedMarketItemInstances() throws SQLException {
        try (Connection connection = connect(); var query = connection.prepareStatement("SELECT instance_id FROM market_item_reservations")) {
            try (var rows = query.executeQuery()) { java.util.Set<String> result = new java.util.HashSet<>(); while (rows.next()) result.add(rows.getString(1)); return java.util.Set.copyOf(result); }
        }
    }

    /** Moves purse funds into the bank and records the purse ledger in the same immediate transaction. */
    public BankResult deposit(UUID profileId, long amountMinor, String operationKey, long nowMillis) throws SQLException {
        validateBankOperation(amountMinor, operationKey);
        return inTransaction(connection -> {
            if (!profileExists(connection, profileId)) return BankResult.UNKNOWN_PROFILE;
            ensureBankAccount(connection, profileId, nowMillis);
            String key = profileId + ":deposit:" + operationKey;
            if (!recordBankOperation(connection, profileId, "DEPOSIT", amountMinor, key, nowMillis))
                return BankResult.ALREADY_APPLIED;
            ApplyResult purse = updateBalance(connection, profileId, -amountMinor);
            if (purse == ApplyResult.INSUFFICIENT_FUNDS) return BankResult.INSUFFICIENT_PURSE;
            if (purse != ApplyResult.APPLIED) return BankResult.UNKNOWN_PROFILE;
            long after = changeBankBalance(connection, profileId, amountMinor, nowMillis);
            ledger(connection, profileId, -amountMinor, "Bank deposit", nowMillis, "bank:" + key);
            setBankOperationBalance(connection, "bank:" + key, after);
            return BankResult.APPLIED;
        });
    }

    /** Moves bank funds into the purse. Failed or repeated operations leave both balances unchanged. */
    public BankResult withdraw(UUID profileId, long amountMinor, String operationKey, long nowMillis) throws SQLException {
        validateBankOperation(amountMinor, operationKey);
        return inTransaction(connection -> {
            if (!profileExists(connection, profileId)) return BankResult.UNKNOWN_PROFILE;
            ensureBankAccount(connection, profileId, nowMillis);
            String key = profileId + ":withdraw:" + operationKey;
            if (!recordBankOperation(connection, profileId, "WITHDRAW", amountMinor, key, nowMillis))
                return BankResult.ALREADY_APPLIED;
            long after;
            try (var update = connection.prepareStatement("UPDATE bank_accounts SET balance_minor=balance_minor-?,version=version+1 "
                    + "WHERE profile_uuid=? AND balance_minor>=?")) {
                update.setLong(1, amountMinor); update.setString(2, profileId.toString()); update.setLong(3, amountMinor);
                if (update.executeUpdate() == 0) return BankResult.INSUFFICIENT_BANK;
            }
            ApplyResult purse = updateBalance(connection, profileId, amountMinor);
            if (purse == ApplyResult.BALANCE_LIMIT) return BankResult.BALANCE_LIMIT;
            if (purse != ApplyResult.APPLIED) return BankResult.UNKNOWN_PROFILE;
            after = bankBalanceMinor(connection, profileId);
            ledger(connection, profileId, amountMinor, "Bank withdrawal", nowMillis, "bank:" + key);
            setBankOperationBalance(connection, "bank:" + key, after);
            return BankResult.APPLIED;
        });
    }

    /** Applies all completed 24-hour compounding periods once, with integer-minor-unit rounding down. */
    public long accrueDailyInterest(UUID profileId, long nowMillis) throws SQLException {
        if (nowMillis < 0) throw new IllegalArgumentException("Invalid timestamp");
        return inTransaction(connection -> {
            if (!profileExists(connection, profileId)) return -1L;
            ensureBankAccount(connection, profileId, nowMillis);
            long balance;
            long last;
            try (var query = connection.prepareStatement("SELECT balance_minor,last_interest_at FROM bank_accounts WHERE profile_uuid=?")) {
                query.setString(1, profileId.toString());
                try (var row = query.executeQuery()) { row.next(); balance = row.getLong(1); last = row.getLong(2); }
            }
            if (nowMillis < last) return 0L;
            long periods = (nowMillis - last) / INTEREST_PERIOD_MILLIS;
            if (periods == 0 || balance == 0) {
                if (periods > 0) advanceInterestTime(connection, profileId, Math.addExact(last, Math.multiplyExact(periods, INTEREST_PERIOD_MILLIS)));
                return 0L;
            }
            long startingBalance = balance;
            for (long i = 0; i < periods; i++) {
                long interest = java.math.BigInteger.valueOf(balance).multiply(java.math.BigInteger.valueOf(BANK_INTEREST_BPS))
                        .divide(java.math.BigInteger.valueOf(10_000)).longValueExact();
                if (interest == 0) continue;
                balance = Math.addExact(balance, interest);
            }
            long earned = balance - startingBalance;
            long newLast = Math.addExact(last, Math.multiplyExact(periods, INTEREST_PERIOD_MILLIS));
            String key = "interest:" + profileId + ":" + newLast;
            if (earned > 0 && recordBankOperation(connection, profileId, "INTEREST", earned, key, nowMillis)) {
                try (var update = connection.prepareStatement("UPDATE bank_accounts SET balance_minor=?,last_interest_at=?,version=version+1 "
                        + "WHERE profile_uuid=? AND last_interest_at=? AND balance_minor=?")) {
                    update.setLong(1, balance); update.setLong(2, newLast); update.setString(3, profileId.toString());
                    update.setLong(4, last); update.setLong(5, startingBalance);
                    if (update.executeUpdate() != 1) throw new SQLException("Bank account changed during interest settlement");
                }
                setBankOperationBalance(connection, "bank:" + key, balance);
            } else {
                advanceInterestTime(connection, profileId, newLast);
            }
            return earned;
        });
    }

    private static void validateBankOperation(long amount, String key) {
        if (amount <= 0 || key == null || key.isBlank()) throw new IllegalArgumentException("Invalid bank operation");
    }

    private void ensureBankAccount(Connection connection, UUID profile, long now) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO bank_accounts(profile_uuid,balance_minor,last_interest_at) VALUES(?,0,?)")) {
            insert.setString(1, profile.toString()); insert.setLong(2, now); insert.executeUpdate();
        }
    }

    private boolean recordBankOperation(Connection connection, UUID profile, String type, long amount,
                                         String key, long now) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO bank_transactions "
                + "(transaction_id,idempotency_key,profile_uuid,type,amount_minor,balance_after_minor,created_at) VALUES(?,?,?,?,?,0,?)")) {
            insert.setString(1, "bank:" + key); insert.setString(2, key); insert.setString(3, profile.toString());
            insert.setString(4, type); insert.setLong(5, amount); insert.setLong(6, now);
            if (insert.executeUpdate() == 1) return true;
        }
        try (var query = connection.prepareStatement("SELECT profile_uuid,type,amount_minor FROM bank_transactions WHERE idempotency_key=?")) {
            query.setString(1, key);
            try (var row = query.executeQuery()) {
                if (!row.next() || !profile.toString().equals(row.getString(1)) || !type.equals(row.getString(2))
                        || row.getLong(3) != amount)
                    throw new SQLException("Idempotency key reused with different bank details: " + key);
            }
        }
        return false;
    }

    private long changeBankBalance(Connection connection, UUID profile, long delta, long now) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE bank_accounts SET balance_minor=balance_minor+?, "
                + "last_interest_at=CASE WHEN balance_minor=0 THEN ? ELSE last_interest_at END, version=version+1 "
                + "WHERE profile_uuid=? AND balance_minor<=?")) {
            update.setLong(1, delta); update.setLong(2, now); update.setString(3, profile.toString()); update.setLong(4, Long.MAX_VALUE - delta);
            if (update.executeUpdate() != 1) throw new SQLException("Bank balance limit exceeded");
        }
        return bankBalanceMinor(connection, profile);
    }

    private long bankBalanceMinor(Connection connection, UUID profile) throws SQLException {
        try (var query = connection.prepareStatement("SELECT balance_minor FROM bank_accounts WHERE profile_uuid=?")) {
            query.setString(1, profile.toString()); try (var row = query.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
        }
    }

    private void setBankOperationBalance(Connection connection, String key, long balance) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE bank_transactions SET balance_after_minor=? WHERE idempotency_key=?")) {
            update.setLong(1, balance); update.setString(2, key); update.executeUpdate();
        }
    }

    private void advanceInterestTime(Connection connection, UUID profile, long newLast) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE bank_accounts SET last_interest_at=?,version=version+1 WHERE profile_uuid=?")) {
            update.setLong(1, newLast); update.setString(2, profile.toString()); update.executeUpdate();
        }
    }

    public ApplyResult applyDelta(UUID profileId, long deltaMinor, String operationKey,
                                  String reason, long nowMillis) throws SQLException {
        if (deltaMinor == 0 || operationKey == null || operationKey.isBlank() || reason == null || reason.isBlank())
            throw new IllegalArgumentException("Invalid wallet operation");
        return inTransaction(connection -> profileExists(connection, profileId)
                ? applyDelta(connection, profileId, null, deltaMinor, operationKey, reason, nowMillis)
                : ApplyResult.UNKNOWN_PROFILE);
    }

    /** Atomically transfers funds and writes both ledger entries; operationKey makes retries safe. */
    public ApplyResult transfer(UUID from, UUID to, long amountMinor, String operationKey,
                                String reason, long nowMillis) throws SQLException {
        if (from.equals(to) || amountMinor <= 0 || operationKey == null || operationKey.isBlank())
            throw new IllegalArgumentException("Invalid wallet transfer");
        return inTransaction(connection -> {
            if (!profileExists(connection, from) || !profileExists(connection, to)) return ApplyResult.UNKNOWN_PROFILE;
            String key = operationKey;
            try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO market_money_transactions "
                    + "(transaction_id,idempotency_key,profile_uuid,counterparty_uuid,delta_minor,reason,created_at) "
                    + "VALUES(?,?,?,?,?,?,?)")) {
                insert.setString(1, "market:" + key);
                insert.setString(2, key);
                insert.setString(3, from.toString());
                insert.setString(4, to.toString());
                insert.setLong(5, -amountMinor);
                insert.setString(6, reason);
                insert.setLong(7, nowMillis);
                if (insert.executeUpdate() == 0) {
                    try (var query = connection.prepareStatement("SELECT profile_uuid,counterparty_uuid,delta_minor,reason FROM market_money_transactions WHERE idempotency_key=?")) {
                        query.setString(1, key);
                        try (var row = query.executeQuery()) {
                            if (!row.next() || !from.toString().equals(row.getString(1)) || !to.toString().equals(row.getString(2))
                                    || row.getLong(3) != -amountMinor || !reason.equals(row.getString(4)))
                                throw new SQLException("Idempotency key reused with different transfer details: " + key);
                        }
                    }
                    return ApplyResult.ALREADY_APPLIED;
                }
            }
            ApplyResult debit = updateBalance(connection, from, -amountMinor);
            if (debit != ApplyResult.APPLIED) return debit;
            ApplyResult credit = updateBalance(connection, to, amountMinor);
            if (credit != ApplyResult.APPLIED) return credit;
            ledger(connection, from, -amountMinor, reason, nowMillis, "market:" + key + ":debit");
            ledger(connection, to, amountMinor, reason, nowMillis, "market:" + key + ":credit");
            return ApplyResult.APPLIED;
        });
    }

    private ApplyResult applyDelta(Connection connection, UUID profile, UUID counterparty, long delta,
                                   String key, String reason, long now) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO market_money_transactions "
                + "(transaction_id,idempotency_key,profile_uuid,counterparty_uuid,delta_minor,reason,created_at) "
                + "VALUES(?,?,?,?,?,?,?)")) {
            insert.setString(1, "market:" + key);
            insert.setString(2, key);
            insert.setString(3, profile.toString());
            insert.setString(4, counterparty == null ? null : counterparty.toString());
            insert.setLong(5, delta);
            insert.setString(6, reason);
            insert.setLong(7, now);
            if (insert.executeUpdate() == 0) {
                try (var query = connection.prepareStatement("SELECT profile_uuid,delta_minor,reason FROM market_money_transactions WHERE idempotency_key=?")) {
                    query.setString(1, key);
                    try (var row = query.executeQuery()) {
                        if (!row.next() || !profile.toString().equals(row.getString(1)) || row.getLong(2) != delta
                                || !reason.equals(row.getString(3)))
                            throw new SQLException("Idempotency key reused with different wallet details: " + key);
                    }
                }
                return ApplyResult.ALREADY_APPLIED;
            }
        }
        ApplyResult result = updateBalance(connection, profile, delta);
        if (result != ApplyResult.APPLIED) return result;
        ledger(connection, profile, delta, reason, now, "market:" + key);
        return ApplyResult.APPLIED;
    }

    ApplyResult updateBalance(Connection connection, UUID profile, long delta) throws SQLException {
        if (delta < 0) {
            long debit;
            try { debit = Math.negateExact(delta); }
            catch (ArithmeticException ex) { return ApplyResult.BALANCE_LIMIT; }
            try (var update = connection.prepareStatement("UPDATE profiles SET balance_minor=balance_minor-? "
                    + "WHERE uuid=? AND balance_minor>=?")) {
                update.setLong(1, debit); update.setString(2, profile.toString()); update.setLong(3, debit);
                if (update.executeUpdate() == 1) return ApplyResult.APPLIED;
            }
            return profileExists(connection, profile) ? ApplyResult.INSUFFICIENT_FUNDS : ApplyResult.UNKNOWN_PROFILE;
        }
        try (var update = connection.prepareStatement("UPDATE profiles SET balance_minor=balance_minor+? "
                + "WHERE uuid=? AND balance_minor<=?")) {
            update.setLong(1, delta); update.setString(2, profile.toString()); update.setLong(3, Long.MAX_VALUE - delta);
            if (update.executeUpdate() == 1) return ApplyResult.APPLIED;
        }
        return profileExists(connection, profile) ? ApplyResult.BALANCE_LIMIT : ApplyResult.UNKNOWN_PROFILE;
    }

    boolean profileExists(Connection connection, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM profiles WHERE uuid=?")) {
            query.setString(1, id.toString()); try (var row = query.executeQuery()) { return row.next(); }
        }
    }

    void ledger(Connection connection, UUID profile, long delta, String reason,
                        long now, String transactionId) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO economy_ledger "
                + "(id,uuid,delta_minor,reason,created_at) VALUES(?,?,?,?,?)")) {
            insert.setString(1, transactionId); insert.setString(2, profile.toString());
            insert.setLong(3, delta); insert.setString(4, reason); insert.setLong(5, now); insert.executeUpdate();
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=10000");
        }
        return connection;
    }

    Connection openConnection() throws SQLException { return connect(); }

    @FunctionalInterface interface SqlWork<T> { T run(Connection connection) throws SQLException; }

    <T> T transact(SqlWork<T> work) throws SQLException { return inTransaction(work); }

    private void runMigration(Connection connection) throws SQLException {
        migrate(connection, 1, "/db/migration/V1__market.sql");
        migrate(connection, 2, "/db/migration/V2__bank.sql");
        migrate(connection, 3, "/db/migration/V3__market_maker.sql");
        migrate(connection, 4, "/db/migration/V4__item_instance_reservations.sql");
        migrate(connection, 5, "/db/migration/V5__asset_identity.sql");
        migrate(connection, 6, "/db/migration/V6__standardized_replay_and_resale.sql");
    }

    private void migrate(Connection connection, int version, String resource) throws SQLException {
        if(tableExists(connection,"market_schema_version"))try(var q=connection.prepareStatement("SELECT 1 FROM market_schema_version WHERE version=?")) {
            q.setInt(1,version);try(var r=q.executeQuery()){if(r.next())return;}
        }
        String sql;
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) throw new SQLException("Missing database migration " + resource);
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) { throw new SQLException("Could not load migration " + resource, ex); }
        try (Statement statement = connection.createStatement()) { statement.execute("BEGIN IMMEDIATE"); }
        try {
            try (Statement statement = connection.createStatement()) {
                for (String command : sql.split(";")) if (!command.isBlank()) statement.execute(command);
            }
            try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO market_schema_version(version,installed_at) VALUES(?,?)")) {
                insert.setInt(1, version); insert.setLong(2, System.currentTimeMillis()); insert.executeUpdate();
            }
            try (Statement statement = connection.createStatement()) { statement.execute("COMMIT"); }
        } catch (SQLException | RuntimeException ex) {
            try (Statement statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
            catch (SQLException rollbackError) { ex.addSuppressed(rollbackError); }
            throw ex;
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            query.setString(1, table); try (var row = query.executeQuery()) { return row.next(); }
        }
    }

    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = connect()) {
            try (Statement statement = connection.createStatement()) { statement.execute("BEGIN IMMEDIATE"); }
            try {
                T result = work.run(connection);
                try (Statement statement = connection.createStatement()) {
                    if (failed(result))
                        statement.execute("ROLLBACK");
                    else statement.execute("COMMIT");
                }
                return result;
            } catch (SQLException | RuntimeException ex) {
                try (Statement statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
                catch (SQLException rollbackError) { ex.addSuppressed(rollbackError); }
                throw ex;
            }
        }
    }

    private static boolean failed(Object result) {
        return result == ApplyResult.INSUFFICIENT_FUNDS || result == ApplyResult.UNKNOWN_PROFILE
                || result == ApplyResult.BALANCE_LIMIT || result == BankResult.INSUFFICIENT_PURSE
                || result == BankResult.INSUFFICIENT_BANK || result == BankResult.UNKNOWN_PROFILE
                || result == BankResult.BALANCE_LIMIT
                || result == NpcLiquidityService.Outcome.INSUFFICIENT_FUNDS
                || result == NpcLiquidityService.Outcome.INSUFFICIENT_LIQUIDITY
                || result == NpcLiquidityService.Outcome.UNKNOWN_PROFILE;
    }

}
