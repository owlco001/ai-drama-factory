PRAGMA journal_mode=WAL;
-- 与 docs/architecture.md §5 逐句一致的原始建表SQL（含FTS5与触发器）
-- 由 AppStartupCallback 在 onOpen 时执行（幂等：IF NOT EXISTS）

CREATE TABLE IF NOT EXISTS memory_short_term (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL,
  role       TEXT NOT NULL,
  content    TEXT NOT NULL,
  tokens     INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_st_session ON memory_short_term(session_id, created_at DESC);

CREATE TABLE IF NOT EXISTS memory_task (
  memory_id  TEXT PRIMARY KEY,
  task_id    TEXT NOT NULL,
  node_id    TEXT NOT NULL,
  agent_id   TEXT NOT NULL,
  content    TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mt_task ON memory_task(task_id, node_id);

CREATE TABLE IF NOT EXISTS memory_long_term (
  key        TEXT PRIMARY KEY,
  content    TEXT NOT NULL,
  tags       TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
);
CREATE VIRTUAL TABLE IF NOT EXISTS memory_long_fts USING fts5(
  content, tags, content='memory_long_term', content_rowid='rowid'
);
CREATE TRIGGER IF NOT EXISTS mem_lt_ai AFTER INSERT ON memory_long_term BEGIN
  INSERT INTO memory_long_fts(rowid, content, tags) VALUES (new.rowid, new.content, new.tags);
END;
CREATE TRIGGER IF NOT EXISTS mem_lt_ad AFTER DELETE ON memory_long_term BEGIN
  INSERT INTO memory_long_fts(memory_long_fts, rowid, content, tags)
  VALUES ('delete', old.rowid, old.content, old.tags);
END;
CREATE TRIGGER IF NOT EXISTS mem_lt_au AFTER UPDATE ON memory_long_term BEGIN
  INSERT INTO memory_long_fts(memory_long_fts, rowid, content, tags)
  VALUES ('delete', old.rowid, old.content, old.tags);
  INSERT INTO memory_long_fts(rowid, content, tags) VALUES (new.rowid, new.content, new.tags);
END;

CREATE TABLE IF NOT EXISTS message_log (
  msg_id    TEXT PRIMARY KEY,
  from_id   TEXT NOT NULL,
  to_id     TEXT NOT NULL,
  type      TEXT NOT NULL,
  payload   TEXT NOT NULL,
  task_id   TEXT,
  reply_to  TEXT,
  status    TEXT NOT NULL DEFAULT 'OK',
  ts        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ml_task ON message_log(task_id, ts);

CREATE TABLE IF NOT EXISTS task_dag (
  task_id   TEXT NOT NULL,
  node_id   TEXT NOT NULL,
  agent_id  TEXT NOT NULL,
  instruction TEXT NOT NULL,
  depends_on  TEXT NOT NULL DEFAULT '',
  state     TEXT NOT NULL DEFAULT 'PENDING',
  started_at INTEGER, ended_at INTEGER,
  PRIMARY KEY (task_id, node_id)
);
