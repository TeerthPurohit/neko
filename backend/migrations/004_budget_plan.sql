-- The user's total budget and income for one calendar month (IST), sent by the Android app with each sync so the agent can pace spending against it.
CREATE TABLE IF NOT EXISTS neko.budget_plans (
  user_id TEXT PRIMARY KEY REFERENCES neko.users(id) ON DELETE CASCADE,
  month TEXT NOT NULL CHECK (month ~ '^[0-9]{4}-[0-9]{2}$'),
  budget_paise BIGINT NOT NULL CHECK (budget_paise > 0 AND budget_paise <= 1000000000),
  income_paise BIGINT NOT NULL DEFAULT 0 CHECK (income_paise >= 0 AND income_paise <= 1000000000),
  updated_at BIGINT NOT NULL
);
