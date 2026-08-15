-- Stats: compatible schema (MySQL 8 / MariaDB)
-- Notes
-- - Store UUID as BINARY(16) for space/index efficiency.
-- - Use UTC consistently (store DATETIME/TIMESTAMP in UTC).
-- - Avoid engine-specific features in core tables.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS mstats_meta (
  `key`   VARCHAR(64) NOT NULL,
  `value` TEXT NOT NULL,
  PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Idempotency ledger for retrying an in-memory flush batch.
CREATE TABLE IF NOT EXISTS mstats_ingest_batch (
  batch_id    BINARY(16) NOT NULL,
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (batch_id),
  KEY idx_ingest_batch_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mstats_dim_player (
  player_uuid      BINARY(16) NOT NULL,
  first_seen_at    DATETIME(3) NOT NULL,
  last_seen_at     DATETIME(3) NOT NULL,
  last_known_name  VARCHAR(32),
  PRIMARY KEY (player_uuid),
  KEY idx_dim_player_last_seen_at (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mstats_dim_command (
  command_id   BIGINT NOT NULL AUTO_INCREMENT,
  command_key  VARCHAR(255) NOT NULL,
  family       VARCHAR(255),
  notes        TEXT,
  PRIMARY KEY (command_id),
  UNIQUE KEY uk_dim_command_command_key (command_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mstats_dim_command_variant (
  variant_id   BIGINT NOT NULL AUTO_INCREMENT,
  command_id   BIGINT NOT NULL,
  variant_key  VARCHAR(255) NOT NULL,
  PRIMARY KEY (variant_id),
  UNIQUE KEY uk_dim_command_variant (command_id, variant_key),
  CONSTRAINT fk_dim_command_variant_command
    FOREIGN KEY (command_id) REFERENCES mstats_dim_command(command_id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mstats_fact_session (
  session_id     BIGINT NOT NULL AUTO_INCREMENT,
  player_uuid    BINARY(16) NOT NULL,
  join_at        DATETIME(3) NOT NULL,
  quit_at        DATETIME(3),
  duration_sec   INT,
  afk_sec        INT,
  join_world     VARCHAR(255),
  quit_world     VARCHAR(255),
  ip_hash        VARBINARY(64),
  client_brand   VARCHAR(255),
  locale         VARCHAR(64),
  PRIMARY KEY (session_id),
  KEY idx_fact_session_player_join (player_uuid, join_at),
  KEY idx_fact_session_join_at (join_at),
  CONSTRAINT fk_fact_session_player
    FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mstats_fact_player_hour (
  player_uuid          BINARY(16) NOT NULL,
  hour_ts              DATETIME(3) NOT NULL,
  playtime_sec         INT NOT NULL DEFAULT 0,
  afk_sec              INT NOT NULL DEFAULT 0,
  active_minutes       SMALLINT NOT NULL DEFAULT 0,
  chat_messages        INT NOT NULL DEFAULT 0,
  chat_chars           INT NOT NULL DEFAULT 0,
  commands_total       INT NOT NULL DEFAULT 0,
  blocks_placed_total  INT NOT NULL DEFAULT 0,
  blocks_broken_total  INT NOT NULL DEFAULT 0,
  distance_m           INT NOT NULL DEFAULT 0,
  teleport_count       INT NOT NULL DEFAULT 0,
  teleport_distance_m  INT NOT NULL DEFAULT 0,
  PRIMARY KEY (player_uuid, hour_ts),
  KEY idx_fact_player_hour_hour_ts (hour_ts),
  CONSTRAINT fk_fact_player_hour_player
    FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mstats_fact_player_day (
  player_uuid    BINARY(16) NOT NULL,
  day            DATE NOT NULL,
  playtime_sec   INT NOT NULL DEFAULT 0,
  sessions       INT NOT NULL DEFAULT 0,
  deaths         INT NOT NULL DEFAULT 0,
  kills_pvp      INT NOT NULL DEFAULT 0,
  kills_mob      INT NOT NULL DEFAULT 0,
  teleport_count       INT NOT NULL DEFAULT 0,
  teleport_distance_m  INT NOT NULL DEFAULT 0,
  PRIMARY KEY (player_uuid, day),
  KEY idx_fact_player_day_day (day),
  CONSTRAINT fk_fact_player_day_player
    FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mstats_fact_command_hour (
  player_uuid  BINARY(16) NOT NULL,
  hour_ts      DATETIME(3) NOT NULL,
  variant_id   BIGINT NOT NULL,
  count        INT NOT NULL,
  PRIMARY KEY (player_uuid, hour_ts, variant_id),
  KEY idx_fact_command_hour_hour_variant (hour_ts, variant_id),
  CONSTRAINT fk_fact_command_hour_player
    FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid)
    ON DELETE CASCADE,
  CONSTRAINT fk_fact_command_hour_variant
    FOREIGN KEY (variant_id) REFERENCES mstats_dim_command_variant(variant_id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mstats_fact_command_day (
  player_uuid  BINARY(16) NOT NULL,
  day          DATE NOT NULL,
  variant_id   BIGINT NOT NULL,
  count        INT NOT NULL,
  PRIMARY KEY (player_uuid, day, variant_id),
  KEY idx_fact_command_day_day_variant (day, variant_id),
  CONSTRAINT fk_fact_command_day_player
    FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid)
    ON DELETE CASCADE,
  CONSTRAINT fk_fact_command_day_variant
    FOREIGN KEY (variant_id) REFERENCES mstats_dim_command_variant(variant_id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mstats_fact_block_group_day (
  player_uuid  BINARY(16) NOT NULL,
  day          DATE NOT NULL,
  group_key    VARCHAR(64) NOT NULL,
  action       SMALLINT NOT NULL,
  count        INT NOT NULL,
  PRIMARY KEY (player_uuid, day, group_key, action),
  KEY idx_fact_block_group_day_day (day),
  CONSTRAINT fk_fact_block_group_day_player
    FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mstats_fact_death_day (
  player_uuid  BINARY(16) NOT NULL,
  day          DATE NOT NULL,
  cause        VARCHAR(64) NOT NULL,
  count        INT NOT NULL,
  PRIMARY KEY (player_uuid, day, cause),
  KEY idx_fact_death_day_day (day),
  CONSTRAINT fk_fact_death_day_player
    FOREIGN KEY (player_uuid) REFERENCES mstats_dim_player(player_uuid)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
