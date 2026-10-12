package network.nexora.trading;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Persistent, funded NPC quotes for low-population Bazaar trading. */
public final class NpcLiquidityService {
    public record Policy(String npcId, String productId, long referencePriceMinor, int spreadBps,
                         long initialInventory, long initialCoinBudgetMinor, long maxInventory,
                         long maxCoinBudgetMinor, long restockItemsPerHour, long restockCoinsPerHourMinor) {
        public Policy {
            Objects.requireNonNull(npcId); Objects.requireNonNull(productId);
            if (npcId.isBlank() || productId.isBlank() || referencePriceMinor <= 0 || spreadBps < 100 || spreadBps > 5_000
                    || initialInventory < 0 || initialCoinBudgetMinor < 0 || maxInventory < initialInventory
                    || maxCoinBudgetMinor < initialCoinBudgetMinor || restockItemsPerHour < 0 || restockCoinsPerHourMinor < 0)
                throw new IllegalArgumentException("Invalid NPC liquidity policy");
        }
    }
    public record Quote(String npcId, String productId, long bidMinor, long bidQuantity,
                        long askMinor, long askQuantity, boolean systemOffer) {}
    public enum Outcome { TRADED, INSUFFICIENT_FUNDS, INSUFFICIENT_LIQUIDITY, LIMIT_NOT_MET, UNKNOWN_PROFILE, ALREADY_APPLIED }
    private static final long HOUR = 3_600_000L;
    private static final int TRADE_FEE_BPS = 100;
    private final MarketplaceDatabase database;

    public NpcLiquidityService(MarketplaceDatabase database) { this.database = Objects.requireNonNull(database); }

    public void configure(Policy policy, long now) throws SQLException {
        database.transact(c -> {
            try (var insert = c.prepareStatement("INSERT OR IGNORE INTO bazaar_market_maker_accounts(npc_id,product_id,inventory,coin_budget_minor,max_inventory,max_coin_budget_minor,restock_items_per_hour,restock_coins_per_hour_minor,hour_window_start) VALUES(?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, policy.npcId()); insert.setString(2, policy.productId()); insert.setLong(3, policy.initialInventory());
                insert.setLong(4, policy.initialCoinBudgetMinor()); insert.setLong(5, policy.maxInventory()); insert.setLong(6, policy.maxCoinBudgetMinor());
                insert.setLong(7, policy.restockItemsPerHour()); insert.setLong(8, policy.restockCoinsPerHourMinor()); insert.setLong(9, now); insert.executeUpdate();
            }
            try (var update = c.prepareStatement("UPDATE bazaar_market_maker_accounts SET max_inventory=?,max_coin_budget_minor=?,restock_items_per_hour=?,restock_coins_per_hour_minor=?,version=version+1 WHERE npc_id=? AND product_id=?")) {
                update.setLong(1, policy.maxInventory()); update.setLong(2, policy.maxCoinBudgetMinor()); update.setLong(3, policy.restockItemsPerHour());
                update.setLong(4, policy.restockCoinsPerHourMinor()); update.setString(5, policy.npcId()); update.setString(6, policy.productId()); update.executeUpdate();
            }
            return null;
        });
    }

    public Quote quote(Policy policy, long now) throws SQLException {
        return database.transact(c -> quote(c, policy, now,
                scalar(c, "SELECT COALESCE(SUM(quantity),0) FROM bazaar_order_fills WHERE product_id=? AND created_at>=?", policy.productId(), now - HOUR),
                scalar(c, "SELECT COALESCE(SUM(quantity),0) FROM bazaar_market_maker_transactions WHERE product_id=? AND created_at>=?", policy.productId(), now - HOUR)));
    }

    /** A consistent display snapshot; trade execution still rechecks its own funded account. */
    public java.util.Map<String, Quote> quotes(java.util.Collection<Policy> policies, long now) throws SQLException {
        if (policies.isEmpty()) return java.util.Map.of();
        return database.transact(c -> {
            java.util.Map<String, Long> playerVolumes = volumes(c, "bazaar_order_fills", now - HOUR);
            java.util.Map<String, Long> npcVolumes = volumes(c, "bazaar_market_maker_transactions", now - HOUR);
            java.util.Map<String, Quote> result = new java.util.HashMap<>();
            for (Policy policy : policies) {
                if (result.containsKey(policy.productId())) throw new IllegalArgumentException("Duplicate quote product");
                result.put(policy.productId(), quote(c, policy, now, playerVolumes.getOrDefault(policy.productId(), 0L), npcVolumes.getOrDefault(policy.productId(), 0L)));
            }
            return java.util.Map.copyOf(result);
        });
    }

    private java.util.Map<String, Long> volumes(Connection c, String table, long since) throws SQLException {
        // Table is selected only by this class's two static call sites, never player input.
        java.util.Map<String, Long> result = new java.util.HashMap<>();
        try (var query = c.prepareStatement("SELECT product_id,SUM(quantity) FROM " + table + " WHERE created_at>=? GROUP BY product_id")) {
            query.setLong(1, since);
            try (var rows = query.executeQuery()) { while (rows.next()) result.put(rows.getString(1), rows.getLong(2)); }
        }
        return result;
    }

    private Quote quote(Connection c, Policy policy, long now, long playerVolume, long npcVolume) throws SQLException {
        Account a = account(c, policy, now, true);
        long half = policy.spreadBps() / 2L;
        long bid = Math.max(1, policy.referencePriceMinor() * (10_000 - half) / 10_000);
        long ask = Math.max(bid + 1, policy.referencePriceMinor() * (10_000 + half) / 10_000);
        long scale = Math.max(1_000, 10_000 - Math.min(90, Math.max(0, playerVolume)) * 100);
        long askQty = Math.min(a.inventory, policy.maxInventory() * scale / 10_000);
        askQty = Math.min(askQty, Math.max(0, policy.maxCoinBudgetMinor() - a.coins) / ask);
        long bidQty = Math.min(a.coins / bid, policy.maxCoinBudgetMinor() * scale / 10_000 / bid);
        long activityCap = Math.max(1, policy.maxInventory() * scale / 10_000);
        askQty = Math.min(askQty, Math.max(0, activityCap - npcVolume));
        bidQty = Math.min(bidQty, Math.max(0, activityCap - npcVolume));
        return new Quote(policy.npcId(), policy.productId(), bid, bidQty, ask, askQty, true);
    }

    /** Executes a player sale into a funded NPC bid. The Paper adapter must escrow the items first. */
    public Outcome sellToNpc(Policy policy, UUID player, long quantity, long limitPrice, String operation, long now) throws SQLException {
        return trade(policy, player, quantity, limitPrice, operation, now, false);
    }

    /** Executes a player purchase from NPC stock and creates a durable item claim. */
    public Outcome buyFromNpc(Policy policy, UUID player, long quantity, long limitPrice, String operation, long now) throws SQLException {
        return trade(policy, player, quantity, limitPrice, operation, now, true);
    }

    private Outcome trade(Policy p, UUID player, long quantity, long limit, String key, long now, boolean playerBuys) throws SQLException {
        if (quantity <= 0 || limit <= 0 || key == null || key.isBlank()) throw new IllegalArgumentException("Invalid NPC order");
        return database.transact(c -> {
            if (!database.profileExists(c, player)) return Outcome.UNKNOWN_PROFILE;
            Account account = account(c, p, now, true);
            long half = p.spreadBps() / 2L;
            long bid = Math.max(1, p.referencePriceMinor() * (10_000 - half) / 10_000);
            long ask = Math.max(bid + 1, p.referencePriceMinor() * (10_000 + half) / 10_000);
            long price = playerBuys ? ask : bid;
            if ((playerBuys && limit < ask) || (!playerBuys && limit > bid)) return Outcome.LIMIT_NOT_MET;
            long maxQty = playerBuys ? account.inventory : account.coins / bid;
            if (quantity > maxQty) return Outcome.INSUFFICIENT_LIQUIDITY;
            long gross = Math.multiplyExact(price, quantity), fee = MoneyMath.fee(gross, TRADE_FEE_BPS);
            String direction = playerBuys ? "PLAYER_BUYS" : "PLAYER_SELLS";
            try (var insert = c.prepareStatement("INSERT OR IGNORE INTO bazaar_market_maker_transactions(transaction_id,idempotency_key,npc_id,product_id,player_uuid,direction,quantity,unit_price_minor,gross_minor,fee_minor,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, "npc:" + key); insert.setString(2, key); insert.setString(3, p.npcId()); insert.setString(4, p.productId());
                insert.setString(5, player.toString()); insert.setString(6, direction); insert.setLong(7, quantity); insert.setLong(8, price); insert.setLong(9, gross); insert.setLong(10, fee); insert.setLong(11, now);
                if (insert.executeUpdate() == 0) return Outcome.ALREADY_APPLIED;
            }
            if (playerBuys) {
                long total = Math.addExact(gross, fee);
                if (database.updateBalance(c, player, -total) == MarketplaceDatabase.ApplyResult.INSUFFICIENT_FUNDS) return Outcome.INSUFFICIENT_FUNDS;
                database.ledger(c, player, -gross, "Bazaar System offer purchase", now, "npc:" + key + ":purchase");
                if (fee > 0) database.ledger(c, player, -fee, "Bazaar System offer fee", now, "npc:" + key + ":fee");
                changeAccount(c, p, -quantity, gross, now);
                try (var claim = c.prepareStatement("INSERT INTO bazaar_claimables(claim_id,profile_uuid,asset_type,product_id,amount_minor,quantity,source_transaction_id,created_at) VALUES(?,?,'ITEMS',?,0,?,?,?)")) {
                    claim.setString(1, UUID.randomUUID().toString()); claim.setString(2, player.toString()); claim.setString(3, p.productId());
                    claim.setLong(4, quantity); claim.setString(5, "npc:" + key); claim.setLong(6, now); claim.executeUpdate();
                }
            } else {
                long net = gross - fee;
                if (database.updateBalance(c, player, net) != MarketplaceDatabase.ApplyResult.APPLIED) throw new SQLException("NPC bid exceeds its funded account");
                database.ledger(c, player, net, "Bazaar System bid proceeds", now, "npc:" + key + ":sale");
                changeAccount(c, p, quantity, -gross, now);
            }
            return Outcome.TRADED;
        });
    }

    private Account account(Connection c, Policy p, long now, boolean restock) throws SQLException {
        try (var query = c.prepareStatement("SELECT inventory,coin_budget_minor,hour_window_start,items_restocked_this_hour,coins_restocked_this_hour_minor,max_inventory,max_coin_budget_minor,restock_items_per_hour,restock_coins_per_hour_minor FROM bazaar_market_maker_accounts WHERE npc_id=? AND product_id=?")) {
            query.setString(1, p.npcId()); query.setString(2, p.productId());
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("NPC market maker is not configured");
                long inventory = row.getLong(1), coins = row.getLong(2), start = row.getLong(3);
                long itemsUsed = row.getLong(4), coinsUsed = row.getLong(5);
                if (restock && now >= start + HOUR) {
                    long periods = Math.max(1, (now - start) / HOUR);
                    long nextStart = start + periods * HOUR;
                    long itemCapacity = Math.max(0, row.getLong(6) - inventory);
                    long coinCapacity = Math.max(0, row.getLong(7) - coins);
                    long addItems = Math.min(itemCapacity, Math.multiplyExact(row.getLong(8), periods));
                    long addCoins = Math.min(coinCapacity, Math.multiplyExact(row.getLong(9), periods));
                    inventory += Math.max(0, addItems); coins += Math.max(0, addCoins);
                    try (var update = c.prepareStatement("UPDATE bazaar_market_maker_accounts SET inventory=?,coin_budget_minor=?,hour_window_start=?,items_restocked_this_hour=0,coins_restocked_this_hour_minor=0,version=version+1 WHERE npc_id=? AND product_id=?")) {
                        update.setLong(1, inventory); update.setLong(2, coins); update.setLong(3, nextStart); update.setString(4, p.npcId()); update.setString(5, p.productId()); update.executeUpdate();
                    }
                }
                return new Account(inventory, coins);
            }
        }
    }

    private void changeAccount(Connection c, Policy p, long itemDelta, long coinDelta, long now) throws SQLException {
        try (var update = c.prepareStatement("UPDATE bazaar_market_maker_accounts SET inventory=inventory+?,coin_budget_minor=coin_budget_minor+?,version=version+1 WHERE npc_id=? AND product_id=? AND inventory+?>=0 AND coin_budget_minor+?>=0 AND inventory+?<=max_inventory AND coin_budget_minor+?<=max_coin_budget_minor")) {
            update.setLong(1, itemDelta); update.setLong(2, coinDelta); update.setString(3, p.npcId()); update.setString(4, p.productId());
            update.setLong(5, itemDelta); update.setLong(6, coinDelta); update.setLong(7, itemDelta); update.setLong(8, coinDelta);
            if (update.executeUpdate() != 1) throw new SQLException("NPC inventory or Coin budget limit would be exceeded");
        }
    }

    private long scalar(Connection c, String sql, String product, long since) throws SQLException {
        try (var q = c.prepareStatement(sql)) { q.setString(1, product); q.setLong(2, since); try (var r = q.executeQuery()) { return r.next() ? r.getLong(1) : 0; } }
    }
    private record Account(long inventory, long coins) {}
}
