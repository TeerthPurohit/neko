-- The user's own part of a bank payment they split with friends (local Splits on the phone). Only this part counts as their spending.
ALTER TABLE neko.transactions ADD COLUMN IF NOT EXISTS personal_share_paise BIGINT CHECK (personal_share_paise IS NULL OR (personal_share_paise >= 0 AND personal_share_paise <= amount_paise));
