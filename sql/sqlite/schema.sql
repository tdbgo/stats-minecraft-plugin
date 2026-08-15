-- Stats: default schema (SQLite)
-- Notes
-- - Intended for “works out of the box” deployments.
-- - Use WAL for better concurrency (see config.yml sqlite pragmas).
-- - Store timestamps as UTC ISO-8601 TEXT for portability.

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS mstats_meta (
  key    TEXT PRIMARY KEY,
  value  TEXT NOT NULL
);

-- Idempotency ledger for retrying an in-memory flush batch.
CREATE TABLE IF NOT EXISTS mstats_ingest_batch (
  batch_id    BLOB NOT NULL PRIMARY KEY,
  created_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS mstats_idx_ingest_batch_created_at ON mstats_ingest_batch (created_at);

CREATE TABLE IF NOT EXISTS mstats_dim_player (
  player_uuid      BLOB NOT NULL, -- 16 bytes (UUID)
  first_seen_at    TEXT NOT NULL,
  last_seen_at     TEXT NOT NULL,
  last_known_name  TEXT,
  PRIMARY KEY (player_uuid)
);

CREATE INDEX IF NOT EXISTS mstats_idx_dim_player_last_seen_at ON mstats_dim_player (last_seen_at);

CREATE TABLE IF NOT EXISTS mstats_dim_command (
  command_id   INTEGER PRIMARY KEY AUTOINCREMENT,
  command_key  TEXT NOT NULL UNIQUE,
  family       TEXT,
  notes        TEXT
);

CREATE TABLE IF NOT EXISTS mstats_dim_command_variant (
  variant_id   INTEGER PRIMARY KEY AUTOINCREMENT,
  command_id   INTEGER NOT NULL,
  variant_key  TEXT NOT NULL,
  UNIQUE (command_id, variant_key),
  FOREIGN KEY (command_id) REFERENCES mstats_dim_command(command_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS mstats_fact_session (
  session_id     INTEGER PRIMARY KEY AUTOINCREMENT,
  player_uuid    BLOB NOT NULL,
  join_at        TEXT NOT NULL,
  quit_at        TEXT,
  duration_sec   INTEGER,
  afk_sec        INTEGER,
  join_world     TEXT,
  quit_world     TEXT,
  ip_hash        BLOB,
  client_brand   TEXT,
  locale         TEXT,
  FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_session_player_join ON mstats_fact_session (player_uuid, join_at);
CREATE INDEX IF NOT EXISTS mstats_idx_fact_session_join_at ON mstats_fact_session (join_at);

CREATE TABLE IF NOT EXISTS mstats_fact_player_hour (
  player_uuid          BLOB NOT NULL,
  hour_ts              TEXT NOT NULL,
  playtime_sec         INTEGER NOT NULL DEFAULT 0,
  afk_sec              INTEGER NOT NULL DEFAULT 0,
  active_minutes       INTEGER NOT NULL DEFAULT 0,
  chat_messages        INTEGER NOT NULL DEFAULT 0,
  chat_chars           INTEGER NOT NULL DEFAULT 0,
  commands_total       INTEGER NOT NULL DEFAULT 0,
  blocks_placed_total  INTEGER NOT NULL DEFAULT 0,
  blocks_broken_total  INTEGER NOT NULL DEFAULT 0,
  distance_m           INTEGER NOT NULL DEFAULT 0,
  teleport_count       INTEGER NOT NULL DEFAULT 0,
  teleport_distance_m  INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (player_uuid, hour_ts),
  FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_player_hour_hour_ts ON mstats_fact_player_hour (hour_ts);

CREATE TABLE IF NOT EXISTS mstats_fact_player_day (
  player_uuid    BLOB NOT NULL,
  day            TEXT NOT NULL, -- YYYY-MM-DD
  playtime_sec   INTEGER NOT NULL DEFAULT 0,
  sessions       INTEGER NOT NULL DEFAULT 0,
  deaths         INTEGER NOT NULL DEFAULT 0,
  kills_pvp      INTEGER NOT NULL DEFAULT 0,
  kills_mob      INTEGER NOT NULL DEFAULT 0,
  teleport_count       INTEGER NOT NULL DEFAULT 0,
  teleport_distance_m  INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (player_uuid, day),
  FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_player_day_day ON mstats_fact_player_day (day);

CREATE TABLE IF NOT EXISTS mstats_fact_command_hour (
  player_uuid  BLOB NOT NULL,
  hour_ts      TEXT NOT NULL,
  variant_id   INTEGER NOT NULL,
  count        INTEGER NOT NULL,
  PRIMARY KEY (player_uuid, hour_ts, variant_id),
  FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE,
  FOREIGN KEY (variant_id) REFERENCES mstats_dim_command_variant(variant_id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_command_hour_hour_variant ON mstats_fact_command_hour (hour_ts, variant_id);

CREATE TABLE IF NOT EXISTS mstats_fact_command_day (
  player_uuid  BLOB NOT NULL,
  day          TEXT NOT NULL, -- YYYY-MM-DD
  variant_id   INTEGER NOT NULL,
  count        INTEGER NOT NULL,
  PRIMARY KEY (player_uuid, day, variant_id),
  FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE,
  FOREIGN KEY (variant_id) REFERENCES mstats_dim_command_variant(variant_id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_command_day_day_variant ON mstats_fact_command_day (day, variant_id);

-- Block groups (action: 0=place, 1=break)
CREATE TABLE IF NOT EXISTS mstats_fact_block_group_day (
  player_uuid  BLOB NOT NULL,
  day          TEXT NOT NULL,
  group_key    TEXT NOT NULL,
  action       INTEGER NOT NULL,
  count        INTEGER NOT NULL,
  PRIMARY KEY (player_uuid, day, group_key, action),
  FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_block_group_day_day ON mstats_fact_block_group_day (day);

CREATE TABLE IF NOT EXISTS mstats_fact_death_day (
  player_uuid  BLOB NOT NULL,
  day          TEXT NOT NULL,
  cause        TEXT NOT NULL,
  count        INTEGER NOT NULL,
  PRIMARY KEY (player_uuid, day, cause),
  FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_death_day_day ON mstats_fact_death_day (day);
