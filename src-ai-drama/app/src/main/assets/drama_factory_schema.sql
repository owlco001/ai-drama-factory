-- drama_factory.db 建表SQL —— 架构§5逐句一致（Room @Database version 1, WAL由Room默认开启）
CREATE TABLE IF NOT EXISTS projects (
  project_id   TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  style_preset TEXT NOT NULL DEFAULT 'cinema',
  episode_plan INTEGER NOT NULL DEFAULT 1,
  budget_shots INTEGER NOT NULL DEFAULT 50,
  created_at   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS assets (
  asset_id     TEXT PRIMARY KEY,
  project_id   TEXT NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
  kind         TEXT NOT NULL,
  parent_id    TEXT,
  pose_role    TEXT,
  prompt       TEXT NOT NULL,
  file_uri     TEXT,
  remote_url   TEXT,
  g1_state     TEXT NOT NULL DEFAULT 'none',
  g2_score     REAL, g2_defects TEXT,
  review_state TEXT NOT NULL DEFAULT 'none',
  reject_reason TEXT, seed INTEGER,
  updated_at   INTEGER NOT NULL,
  source       TEXT NOT NULL DEFAULT 'generated',
  image_uri    TEXT,
  video_uri    TEXT,
  reference_image_uri TEXT,
  enriched_prompt TEXT   -- v1.9.12：LLM 扩写视觉描述（生图实际用的主体描述），可空
);
CREATE INDEX IF NOT EXISTS idx_assets_proj ON assets(project_id, kind);

CREATE TABLE IF NOT EXISTS shots (
  shot_id      TEXT PRIMARY KEY,
  episode_id   TEXT NOT NULL,
  project_id   TEXT NOT NULL,
  shot_no      INTEGER NOT NULL,
  dialogue TEXT, narration TEXT, action TEXT,
  beat_ref TEXT, carry_over TEXT,
  first_asset_ids TEXT NOT NULL DEFAULT '[]',
  last_asset_ids  TEXT NOT NULL DEFAULT '[]',
  sb_check     TEXT NOT NULL DEFAULT 'pending',
  first_image_uri TEXT,
  last_image_uri  TEXT,
  reference_video_uri TEXT,
  UNIQUE(episode_id, shot_no)
);

CREATE TABLE IF NOT EXISTS render_tasks (
  shot_id        TEXT PRIMARY KEY REFERENCES shots(shot_id),
  episode_id     TEXT NOT NULL,
  state          TEXT NOT NULL DEFAULT 'PENDING',
  provider_task_id TEXT,
  attempt        INTEGER NOT NULL DEFAULT 0,
  blocked_reason TEXT, fail_reason TEXT,
  local_file_uri TEXT, file_size INTEGER NOT NULL DEFAULT 0,
  submitted_at   INTEGER, completed_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_rt_ep ON render_tasks(episode_id, state);

CREATE TABLE IF NOT EXISTS provider_configs (
  config_id    TEXT PRIMARY KEY,
  channel      TEXT NOT NULL,
  provider_id  TEXT NOT NULL,
  model        TEXT NOT NULL,
  key_cipher   BLOB NOT NULL,
  key_masked   TEXT NOT NULL,
  extra_params TEXT NOT NULL DEFAULT '{}',
  is_verified  INTEGER NOT NULL DEFAULT 0,
  updated_at   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS episodes (
  episode_id   TEXT PRIMARY KEY,
  project_id   TEXT NOT NULL,
  ep_no        INTEGER NOT NULL,
  script_json  TEXT, storyboard_report TEXT,
  review_passed INTEGER NOT NULL DEFAULT 0,
  stage_flags  TEXT NOT NULL DEFAULT '{}',
  UNIQUE(project_id, ep_no)
);
