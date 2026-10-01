ALTER TABLE neko.transactions ADD COLUMN IF NOT EXISTS spending_treatment TEXT NOT NULL DEFAULT 'AUTO';
ALTER TABLE neko.transactions ADD COLUMN IF NOT EXISTS related_transaction_id TEXT;
ALTER TABLE neko.transactions ADD COLUMN IF NOT EXISTS principal_paise BIGINT;
