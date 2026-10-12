-- One physical, uniquely identified Nexora item may belong to exactly one marketplace escrow.
CREATE TABLE IF NOT EXISTS market_item_reservations (
  instance_id TEXT PRIMARY KEY,
  market TEXT NOT NULL CHECK (market IN ('BAZAAR','AUCTION')),
  listing_id TEXT NOT NULL,
  owner_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  state TEXT NOT NULL CHECK (state IN ('LISTED','CLAIMABLE')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS market_item_reservations_listing_idx
  ON market_item_reservations(market,listing_id,state);

CREATE TABLE IF NOT EXISTS standardized_bazaar_orders (
  order_id TEXT PRIMARY KEY,
  profile_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  product_id TEXT NOT NULL,
  side TEXT NOT NULL CHECK(side IN ('BUY','SELL')),
  unit_price_minor INTEGER NOT NULL CHECK(unit_price_minor>0),
  original_quantity INTEGER NOT NULL CHECK(original_quantity>0),
  remaining_quantity INTEGER NOT NULL CHECK(remaining_quantity>=0 AND remaining_quantity<=original_quantity),
  filled_quantity INTEGER NOT NULL DEFAULT 0 CHECK(filled_quantity>=0 AND filled_quantity<=original_quantity),
  reserved_minor INTEGER NOT NULL DEFAULT 0 CHECK(reserved_minor>=0),
  status TEXT NOT NULL CHECK(status IN ('OPEN','PARTIAL','FILLED','CANCELLED')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  CHECK(remaining_quantity+filled_quantity=original_quantity)
);
CREATE INDEX IF NOT EXISTS standardized_bazaar_book_idx
  ON standardized_bazaar_orders(product_id,side,status,unit_price_minor,created_at,order_id);
CREATE INDEX IF NOT EXISTS standardized_bazaar_owner_idx
  ON standardized_bazaar_orders(profile_uuid,status,created_at);

CREATE TABLE IF NOT EXISTS standardized_bazaar_order_items (
  instance_id TEXT PRIMARY KEY,
  order_id TEXT NOT NULL REFERENCES standardized_bazaar_orders(order_id),
  item_snapshot BLOB NOT NULL,
  item_sha256 TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('ESCROW','CLAIMABLE')),
  claim_id TEXT,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS standardized_bazaar_claims (
  claim_id TEXT PRIMARY KEY,
  profile_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  order_id TEXT NOT NULL REFERENCES standardized_bazaar_orders(order_id),
  instance_id TEXT NOT NULL UNIQUE,
  item_snapshot BLOB NOT NULL,
  source_id TEXT NOT NULL UNIQUE,
  claimed_at INTEGER,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS standardized_bazaar_claim_owner_idx
  ON standardized_bazaar_claims(profile_uuid,claimed_at,created_at);

