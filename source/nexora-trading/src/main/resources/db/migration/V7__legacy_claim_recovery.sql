-- Receipts bridge old tagged inventory deliveries to the current journal protocol.
CREATE TABLE legacy_claim_reconciliations (
  operation_key TEXT PRIMARY KEY,
  owner_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  claim_id TEXT NOT NULL UNIQUE REFERENCES bazaar_claimables(claim_id),
  product_id TEXT NOT NULL REFERENCES bazaar_products(product_id),
  original_quantity INTEGER NOT NULL CHECK(original_quantity>0),
  delivered_quantity INTEGER NOT NULL CHECK(delivered_quantity>0 AND delivered_quantity<=original_quantity),
  created_at INTEGER NOT NULL
);
