-- Stats: canonical schema (Postgres)
-- Notes
-- - Prefer UTC everywhere (timestamptz).
-- - Keep schema “portable”: avoid jsonb/enum in core tables.
-- - Consider RANGE partitioning by day/month for long retention.

BEGIN;

CREATE TABLE IF NOT EXISTS mstats_meta (
  key    TEXT PRIMARY KEY,
  value  TEXT NOT NULL
);

-- Idempotency ledger for retrying an in-memory flush batch.
CREATE TABLE IF NOT EXISTS mstats_ingest_batch (
  batch_id    UUID PRIMARY KEY,
  created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS mstats_idx_ingest_batch_created_at ON mstats_ingest_batch (created_at);

CREATE TABLE IF NOT EXISTS mstats_dim_player (
  player_uuid      UUID PRIMARY KEY,
  first_seen_at    TIMESTAMPTZ NOT NULL,
  last_seen_at     TIMESTAMPTZ NOT NULL,
  last_known_name  VARCHAR(32)
);

CREATE INDEX IF NOT EXISTS mstats_idx_dim_player_last_seen_at ON mstats_dim_player (last_seen_at);

CREATE TABLE IF NOT EXISTS mstats_dim_command (
  command_id   BIGSERIAL PRIMARY KEY,
  command_key  TEXT NOT NULL UNIQUE,
  family       TEXT,
  notes        TEXT
);

CREATE TABLE IF NOT EXISTS mstats_dim_command_variant (
  variant_id   BIGSERIAL PRIMARY KEY,
  command_id   BIGINT NOT NULL REFERENCES mstats_dim_command(command_id) ON DELETE CASCADE,
  variant_key  TEXT NOT NULL,
  UNIQUE (command_id, variant_key)
);

-- Sessions
CREATE TABLE IF NOT EXISTS mstats_fact_session (
  session_id     BIGSERIAL PRIMARY KEY,
  player_uuid    UUID NOT NULL REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE,
  join_at        TIMESTAMPTZ NOT NULL,
  quit_at        TIMESTAMPTZ,
  duration_sec   INTEGER,
  afk_sec        INTEGER,
  join_world     TEXT,
  quit_world     TEXT,
  ip_hash        BYTEA,
  client_brand   TEXT,
  locale         TEXT
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_session_player_join ON mstats_fact_session (player_uuid, join_at);
CREATE INDEX IF NOT EXISTS mstats_idx_fact_session_join_at ON mstats_fact_session (join_at);

-- Player hour bucket (only create rows for hours with activity)
CREATE TABLE IF NOT EXISTS mstats_fact_player_hour (
  player_uuid          UUID NOT NULL REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE,
  hour_ts              TIMESTAMPTZ NOT NULL,
  playtime_sec         INTEGER NOT NULL DEFAULT 0,
  afk_sec              INTEGER NOT NULL DEFAULT 0,
  active_minutes       SMALLINT NOT NULL DEFAULT 0,
  chat_messages        INTEGER NOT NULL DEFAULT 0,
  chat_chars           INTEGER NOT NULL DEFAULT 0,
  commands_total       INTEGER NOT NULL DEFAULT 0,
  blocks_placed_total  INTEGER NOT NULL DEFAULT 0,
  blocks_broken_total  INTEGER NOT NULL DEFAULT 0,
  distance_m           INTEGER NOT NULL DEFAULT 0,
  teleport_count       INTEGER NOT NULL DEFAULT 0,
  teleport_distance_m  INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (player_uuid, hour_ts)
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_player_hour_hour_ts ON mstats_fact_player_hour (hour_ts);

-- Player day bucket
CREATE TABLE IF NOT EXISTS mstats_fact_player_day (
  player_uuid    UUID NOT NULL REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE,
  day            DATE NOT NULL,
  playtime_sec   INTEGER NOT NULL DEFAULT 0,
  sessions       INTEGER NOT NULL DEFAULT 0,
  deaths         INTEGER NOT NULL DEFAULT 0,
  kills_pvp      INTEGER NOT NULL DEFAULT 0,
  kills_mob      INTEGER NOT NULL DEFAULT 0,
  teleport_count       INTEGER NOT NULL DEFAULT 0,
  teleport_distance_m  INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (player_uuid, day)
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_player_day_day ON mstats_fact_player_day (day);

-- Command usage (hour/day)
CREATE TABLE IF NOT EXISTS mstats_fact_command_hour (
  player_uuid  UUID NOT NULL REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE,
  hour_ts      TIMESTAMPTZ NOT NULL,
  variant_id   BIGINT NOT NULL REFERENCES mstats_dim_command_variant(variant_id) ON DELETE RESTRICT,
  count        INTEGER NOT NULL,
  PRIMARY KEY (player_uuid, hour_ts, variant_id)
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_command_hour_hour_variant ON mstats_fact_command_hour (hour_ts, variant_id);

CREATE TABLE IF NOT EXISTS mstats_fact_command_day (
  player_uuid  UUID NOT NULL REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE,
  day          DATE NOT NULL,
  variant_id   BIGINT NOT NULL REFERENCES mstats_dim_command_variant(variant_id) ON DELETE RESTRICT,
  count        INTEGER NOT NULL,
  PRIMARY KEY (player_uuid, day, variant_id)
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_command_day_day_variant ON mstats_fact_command_day (day, variant_id);

-- Block groups (action: 0=place, 1=break)
CREATE TABLE IF NOT EXISTS mstats_fact_block_group_day (
  player_uuid  UUID NOT NULL REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE,
  day          DATE NOT NULL,
  group_key    VARCHAR(64) NOT NULL,
  action       SMALLINT NOT NULL,
  count        INTEGER NOT NULL,
  PRIMARY KEY (player_uuid, day, group_key, action)
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_block_group_day_day ON mstats_fact_block_group_day (day);

-- Death causes
CREATE TABLE IF NOT EXISTS mstats_fact_death_day (
  player_uuid  UUID NOT NULL REFERENCES mstats_dim_player(player_uuid) ON DELETE CASCADE,
  day          DATE NOT NULL,
  cause        VARCHAR(64) NOT NULL,
  count        INTEGER NOT NULL,
  PRIMARY KEY (player_uuid, day, cause)
);

CREATE INDEX IF NOT EXISTS mstats_idx_fact_death_day_day ON mstats_fact_death_day (day);

COMMIT;
