-- Keep historical snapshots while allowing a delivered physical instance to be sold again.
CREATE TABLE standardized_bazaar_order_items_v6 (
  instance_id TEXT NOT NULL,
  order_id TEXT NOT NULL REFERENCES standardized_bazaar_orders(order_id),
  item_snapshot BLOB NOT NULL,
  item_sha256 TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('ESCROW','CLAIMABLE')),
  claim_id TEXT,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY(order_id,instance_id)
);
INSERT INTO standardized_bazaar_order_items_v6 SELECT * FROM standardized_bazaar_order_items;
DROP TABLE standardized_bazaar_order_items;
ALTER TABLE standardized_bazaar_order_items_v6 RENAME TO standardized_bazaar_order_items;
CREATE INDEX standardized_item_escrow_idx ON standardized_bazaar_order_items(instance_id,state);
CREATE TABLE standardized_bazaar_claims_v6 (
  claim_id TEXT PRIMARY KEY,
  profile_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  order_id TEXT NOT NULL REFERENCES standardized_bazaar_orders(order_id),
  instance_id TEXT NOT NULL,
  item_snapshot BLOB NOT NULL,
  source_id TEXT NOT NULL UNIQUE,
  claimed_at INTEGER,
  created_at INTEGER NOT NULL
);
INSERT INTO standardized_bazaar_claims_v6 SELECT * FROM standardized_bazaar_claims;
DROP TABLE standardized_bazaar_claims;
ALTER TABLE standardized_bazaar_claims_v6 RENAME TO standardized_bazaar_claims;
CREATE UNIQUE INDEX standardized_pending_instance_idx ON standardized_bazaar_claims(instance_id) WHERE claimed_at IS NULL;
CREATE INDEX standardized_bazaar_claim_owner_idx ON standardized_bazaar_claims(profile_uuid,claimed_at,created_at);
CREATE TABLE standardized_bazaar_transactions (
  operation_key TEXT PRIMARY KEY,
  owner_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  request_hash TEXT NOT NULL,
  order_id TEXT NOT NULL REFERENCES standardized_bazaar_orders(order_id),
  outcome TEXT NOT NULL,
  filled INTEGER NOT NULL CHECK(filled>=0),
  remaining INTEGER NOT NULL CHECK(remaining>=0),
  created_at INTEGER NOT NULL
);
