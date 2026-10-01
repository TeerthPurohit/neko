CREATE TABLE IF NOT EXISTS neko.agent_corrections (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES neko.users(id) ON DELETE CASCADE,
  scope TEXT NOT NULL CHECK (scope IN ('all','classification','ledger','budget','summary','followup','chat')),
  behavior TEXT NOT NULL,
  correction TEXT NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS agent_corrections_user ON neko.agent_corrections(user_id,created_at);
