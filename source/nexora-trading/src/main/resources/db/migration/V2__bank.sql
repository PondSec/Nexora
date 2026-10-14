CREATE TABLE IF NOT EXISTS bank_accounts (
  profile_uuid TEXT PRIMARY KEY REFERENCES profiles(uuid),
  balance_minor INTEGER NOT NULL DEFAULT 0 CHECK(balance_minor >= 0),
  last_interest_at INTEGER NOT NULL,
  version INTEGER NOT NULL DEFAULT 0 CHECK(version >= 0)
);
CREATE TABLE IF NOT EXISTS bank_transactions (
  transaction_id TEXT PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  profile_uuid TEXT NOT NULL REFERENCES profiles(uuid),
  type TEXT NOT NULL CHECK(type IN ('DEPOSIT','WITHDRAW','INTEREST')),
  amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
  balance_after_minor INTEGER NOT NULL CHECK(balance_after_minor >= 0),
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS bank_transactions_profile_idx
  ON bank_transactions(profile_uuid,created_at,transaction_id);
