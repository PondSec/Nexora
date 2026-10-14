-- Nexora marketplace schema. Wallet ownership remains profiles.balance_minor in NexoraCore.
CREATE TABLE IF NOT EXISTS market_schema_version (
  version INTEGER PRIMARY KEY,
  installed_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS market_money_transactions (
  transaction_id TEXT PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  profile_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  counterparty_uuid TEXT REFERENCES profiles(uuid),
  delta_minor INTEGER NOT NULL,
  reason TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS bazaar_products (
  product_id TEXT PRIMARY KEY,
  material_key TEXT NOT NULL UNIQUE,
  enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)),
  min_order_quantity INTEGER NOT NULL DEFAULT 1 CHECK (min_order_quantity > 0),
  max_order_quantity INTEGER NOT NULL DEFAULT 1000000 CHECK (max_order_quantity >= min_order_quantity),
  price_tick_minor INTEGER NOT NULL DEFAULT 1 CHECK (price_tick_minor > 0),
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS bazaar_orders (
  order_id TEXT PRIMARY KEY,
  profile_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  product_id TEXT NOT NULL REFERENCES bazaar_products(product_id),
  side TEXT NOT NULL CHECK (side IN ('BUY','SELL')),
  unit_price_minor INTEGER NOT NULL CHECK (unit_price_minor > 0),
  original_quantity INTEGER NOT NULL CHECK (original_quantity > 0),
  remaining_quantity INTEGER NOT NULL CHECK (remaining_quantity >= 0 AND remaining_quantity <= original_quantity),
  filled_quantity INTEGER NOT NULL DEFAULT 0 CHECK (filled_quantity >= 0 AND filled_quantity <= original_quantity),
  status TEXT NOT NULL CHECK (status IN ('OPEN','PARTIAL','FILLED','CANCELLED')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (remaining_quantity + filled_quantity = original_quantity)
);
CREATE INDEX IF NOT EXISTS bazaar_order_book_idx
  ON bazaar_orders(product_id,side,status,unit_price_minor,created_at,order_id);
CREATE INDEX IF NOT EXISTS bazaar_profile_orders_idx ON bazaar_orders(profile_uuid,status,created_at);

CREATE TABLE IF NOT EXISTS bazaar_escrow (
  escrow_id TEXT PRIMARY KEY,
  order_id TEXT NOT NULL UNIQUE REFERENCES bazaar_orders(order_id),
  profile_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  asset_type TEXT NOT NULL CHECK (asset_type IN ('COINS','ITEMS')),
  product_id TEXT REFERENCES bazaar_products(product_id),
  amount_minor INTEGER NOT NULL DEFAULT 0 CHECK (amount_minor >= 0),
  quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
  updated_at INTEGER NOT NULL,
  version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK ((asset_type='COINS' AND product_id IS NULL AND quantity=0) OR
         (asset_type='ITEMS' AND product_id IS NOT NULL AND amount_minor=0))
);

CREATE TABLE IF NOT EXISTS bazaar_order_fills (
  fill_id TEXT PRIMARY KEY,
  operation_id TEXT NOT NULL UNIQUE,
  buy_order_id TEXT NOT NULL REFERENCES bazaar_orders(order_id),
  sell_order_id TEXT NOT NULL REFERENCES bazaar_orders(order_id),
  buyer_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  seller_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  product_id TEXT NOT NULL REFERENCES bazaar_products(product_id),
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  unit_price_minor INTEGER NOT NULL CHECK (unit_price_minor > 0),
  gross_minor INTEGER NOT NULL CHECK (gross_minor > 0),
  seller_fee_minor INTEGER NOT NULL CHECK (seller_fee_minor >= 0),
  created_at INTEGER NOT NULL,
  CHECK (buyer_uuid <> seller_uuid),
  CHECK (gross_minor = quantity * unit_price_minor)
);
CREATE INDEX IF NOT EXISTS bazaar_fills_product_time_idx ON bazaar_order_fills(product_id,created_at);

CREATE TABLE IF NOT EXISTS bazaar_claimables (
  claim_id TEXT PRIMARY KEY,
  profile_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  asset_type TEXT NOT NULL CHECK (asset_type IN ('COINS','ITEMS')),
  product_id TEXT REFERENCES bazaar_products(product_id),
  amount_minor INTEGER NOT NULL DEFAULT 0 CHECK (amount_minor >= 0),
  quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
  source_transaction_id TEXT NOT NULL UNIQUE,
  claimed_at INTEGER,
  created_at INTEGER NOT NULL,
  CHECK ((asset_type='COINS' AND product_id IS NULL AND quantity=0) OR
         (asset_type='ITEMS' AND product_id IS NOT NULL AND amount_minor=0))
);
CREATE INDEX IF NOT EXISTS bazaar_claimable_profile_idx ON bazaar_claimables(profile_uuid,claimed_at,created_at);

CREATE TABLE IF NOT EXISTS bazaar_transactions (
  transaction_id TEXT PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  transaction_type TEXT NOT NULL,
  profile_uuid TEXT REFERENCES profiles(uuid),
  order_id TEXT REFERENCES bazaar_orders(order_id),
  amount_minor INTEGER NOT NULL DEFAULT 0,
  quantity INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS bazaar_price_history (
  history_id INTEGER PRIMARY KEY AUTOINCREMENT,
  product_id TEXT NOT NULL REFERENCES bazaar_products(product_id),
  fill_id TEXT NOT NULL UNIQUE REFERENCES bazaar_order_fills(fill_id),
  price_minor INTEGER NOT NULL CHECK (price_minor > 0),
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  traded_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS bazaar_price_history_time_idx ON bazaar_price_history(product_id,traded_at);
CREATE TABLE IF NOT EXISTS bazaar_audit_log (
  audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
  operation_id TEXT NOT NULL,
  actor_uuid TEXT,
  action TEXT NOT NULL,
  details TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS auctions (
  auction_id TEXT PRIMARY KEY,
  seller_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  mode TEXT NOT NULL CHECK (mode IN ('BIN','BID')),
  status TEXT NOT NULL CHECK (status IN ('DRAFT','ACTIVE','SOLD','ENDED','EXPIRED','CANCELLED','CLAIMED')),
  start_price_minor INTEGER NOT NULL CHECK (start_price_minor > 0),
  bin_price_minor INTEGER CHECK (bin_price_minor IS NULL OR bin_price_minor > 0),
  current_bid_minor INTEGER NOT NULL DEFAULT 0 CHECK (current_bid_minor >= 0),
  highest_bidder_uuid TEXT REFERENCES profiles(uuid),
  starts_at INTEGER NOT NULL,
  ends_at INTEGER NOT NULL CHECK (ends_at > starts_at),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE INDEX IF NOT EXISTS auction_browser_idx ON auctions(status,mode,ends_at,created_at);
CREATE INDEX IF NOT EXISTS auction_seller_idx ON auctions(seller_uuid,status,created_at);

CREATE TABLE IF NOT EXISTS auction_items (
  auction_id TEXT PRIMARY KEY REFERENCES auctions(auction_id),
  snapshot_version INTEGER NOT NULL CHECK (snapshot_version > 0),
  item_type TEXT NOT NULL,
  item_snapshot BLOB NOT NULL,
  item_sha256 TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS auction_bids (
  bid_id TEXT PRIMARY KEY,
  auction_id TEXT NOT NULL REFERENCES auctions(auction_id),
  bidder_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
  status TEXT NOT NULL CHECK (status IN ('LEADING','OUTBID','WON','REFUNDED','CLAIMED')),
  escrow_minor INTEGER NOT NULL CHECK (escrow_minor >= 0),
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS auction_bid_history_idx ON auction_bids(auction_id,created_at);
CREATE TABLE IF NOT EXISTS auction_claimables (
  claim_id TEXT PRIMARY KEY,
  profile_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  auction_id TEXT NOT NULL REFERENCES auctions(auction_id),
  asset_type TEXT NOT NULL CHECK (asset_type IN ('ITEM','COINS')),
  amount_minor INTEGER NOT NULL DEFAULT 0 CHECK (amount_minor >= 0),
  item_snapshot BLOB,
  source_transaction_id TEXT NOT NULL UNIQUE,
  claimed_at INTEGER,
  created_at INTEGER NOT NULL,
  CHECK ((asset_type='ITEM' AND item_snapshot IS NOT NULL AND amount_minor=0) OR
         (asset_type='COINS' AND item_snapshot IS NULL))
);
CREATE INDEX IF NOT EXISTS auction_claimable_profile_idx ON auction_claimables(profile_uuid,claimed_at,created_at);
CREATE TABLE IF NOT EXISTS auction_transactions (
  transaction_id TEXT PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  auction_id TEXT NOT NULL REFERENCES auctions(auction_id),
  actor_uuid TEXT REFERENCES profiles(uuid),
  action TEXT NOT NULL,
  amount_minor INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS auction_history (
  history_id INTEGER PRIMARY KEY AUTOINCREMENT,
  auction_id TEXT NOT NULL REFERENCES auctions(auction_id),
  seller_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  buyer_uuid TEXT REFERENCES profiles(uuid),
  final_price_minor INTEGER NOT NULL DEFAULT 0 CHECK (final_price_minor >= 0),
  result TEXT NOT NULL CHECK (result IN ('SOLD','UNSOLD','CANCELLED')),
  closed_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS auction_audit_log (
  audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
  operation_id TEXT NOT NULL,
  actor_uuid TEXT,
  action TEXT NOT NULL,
  details TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
