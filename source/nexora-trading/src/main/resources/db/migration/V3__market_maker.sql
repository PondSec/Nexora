CREATE TABLE IF NOT EXISTS bazaar_market_maker_accounts (
  npc_id TEXT NOT NULL,
  product_id TEXT NOT NULL REFERENCES bazaar_products(product_id),
  inventory INTEGER NOT NULL DEFAULT 0 CHECK(inventory >= 0),
  coin_budget_minor INTEGER NOT NULL DEFAULT 0 CHECK(coin_budget_minor >= 0),
  max_inventory INTEGER NOT NULL CHECK(max_inventory >= 0),
  max_coin_budget_minor INTEGER NOT NULL CHECK(max_coin_budget_minor >= 0),
  restock_items_per_hour INTEGER NOT NULL DEFAULT 0 CHECK(restock_items_per_hour >= 0),
  restock_coins_per_hour_minor INTEGER NOT NULL DEFAULT 0 CHECK(restock_coins_per_hour_minor >= 0),
  hour_window_start INTEGER NOT NULL,
  items_restocked_this_hour INTEGER NOT NULL DEFAULT 0 CHECK(items_restocked_this_hour >= 0),
  coins_restocked_this_hour_minor INTEGER NOT NULL DEFAULT 0 CHECK(coins_restocked_this_hour_minor >= 0),
  version INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(npc_id,product_id)
);
CREATE TABLE IF NOT EXISTS bazaar_market_maker_transactions (
  transaction_id TEXT PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  npc_id TEXT NOT NULL,
  product_id TEXT NOT NULL REFERENCES bazaar_products(product_id),
  player_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  direction TEXT NOT NULL CHECK(direction IN ('PLAYER_BUYS','PLAYER_SELLS')),
  quantity INTEGER NOT NULL CHECK(quantity > 0),
  unit_price_minor INTEGER NOT NULL CHECK(unit_price_minor > 0),
  gross_minor INTEGER NOT NULL CHECK(gross_minor > 0),
  fee_minor INTEGER NOT NULL CHECK(fee_minor >= 0),
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS market_maker_product_time_idx ON bazaar_market_maker_transactions(product_id,created_at);
