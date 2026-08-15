# Storage schema v2

The current schema version is `2`. SQLite, PostgreSQL, and MySQL/MariaDB share the same logical structure. Static DDL is in `sql/sqlite/schema.sql`, `sql/postgres/schema.sql`, and `sql/mysql/schema.sql`.

Physical table names carry the `database.tablePrefix`. The names below assume the default prefix `mstats_`.

## Metadata and idempotency

- `mstats_meta`
  - PK: `key`
  - Currently stores `schema_version` and `plugin_version`
- `mstats_ingest_batch`
  - PK: `batch_id` — `UUID` on PostgreSQL, `BINARY(16)` on MySQL/MariaDB, `BLOB` on SQLite
  - Column: `created_at`
  - Purpose: prevents a retried flush snapshot from incrementing aggregates twice
  - Index: `created_at`

The `ingest_batch` row and the fact-table changes are part of the same transaction.

## Dimensions

- `mstats_dim_player`
  - PK: `player_uuid`
  - `first_seen_at`, `last_seen_at`, `last_known_name`
- `mstats_dim_command`
  - PK: `command_id`; UK: `command_key`
  - `family`, `notes`. `family` is set to `worldedit` for keys beginning `worldedit:`, otherwise null.
- `mstats_dim_command_variant`
  - PK: `variant_id`; FK: `command_id`
  - UK: `(command_id, variant_key)`
  - Example `variant_key` values: `mode=creative`, `material=stone`, `target_kind=other`. Empty string when no argument is captured.

## Sessions

- `mstats_fact_session`
  - PK: auto-increment `session_id`
  - `player_uuid`, `join_at`, `quit_at`, `duration_sec`, `afk_sec`, `join_world`, `quit_world`
  - Indexes: `(player_uuid, join_at)`, `join_at`
  - `ip_hash`, `client_brand`, and `locale` exist for compatibility. **This collector never writes them**; they remain null.

## Player time buckets

- `mstats_fact_player_hour`
  - PK: `(player_uuid, hour_ts)`
  - `playtime_sec`, `afk_sec`, `active_minutes`
  - `chat_messages`, `chat_chars`, `commands_total`
  - `blocks_placed_total`, `blocks_broken_total`
  - `distance_m`, `teleport_count`, `teleport_distance_m`
  - Index: `hour_ts`
- `mstats_fact_player_day`
  - PK: `(player_uuid, day)`
  - `playtime_sec`, `sessions`, `deaths`, `kills_pvp`, `kills_mob`
  - `teleport_count`, `teleport_distance_m`
  - Index: `day`

`active_minutes` is the population count of a cumulative per-hour bitset. On upsert it takes the maximum of the existing and incoming values rather than summing them, so a retried or split batch cannot inflate it past 60.

All other counters are summed on upsert.

## Commands, blocks, deaths

- `mstats_fact_command_hour`: PK `(player_uuid, hour_ts, variant_id)`, `count`
- `mstats_fact_command_day`: PK `(player_uuid, day, variant_id)`, `count`
- `mstats_fact_block_group_day`: PK `(player_uuid, day, group_key, action)`, `count`; `action` is `0` for place and `1` for break
- `mstats_fact_death_day`: PK `(player_uuid, day, cause)`, `count`

Raw command lines, individual block materials, coordinates, and chat bodies do not appear anywhere in this schema.

## v1 to v2

Version 2 adds:

- `mstats_ingest_batch`
- `mstats_fact_player_hour.teleport_count`
- `mstats_fact_player_hour.teleport_distance_m`
- `mstats_fact_player_day.teleport_count`
- `mstats_fact_player_day.teleport_distance_m`

With `autoCreateTables: true` the ingest table is created. If the existing player hour and day tables lack the teleport columns, `autoMigrate: true` adds them with `ALTER TABLE ... ADD COLUMN`; `autoMigrate: false` fails activation instead.

## Type mapping

| Concept | PostgreSQL | MySQL/MariaDB | SQLite |
| --- | --- | --- | --- |
| UUID | `UUID` | `BINARY(16)` | `BLOB(16)` |
| Timestamp | `TIMESTAMPTZ` | `DATETIME(3)` | UTC ISO-8601 `TEXT` |
| Date | `DATE` | `DATE` | `YYYY-MM-DD` `TEXT` |

Hour buckets are stored as the UTC hour start. Day buckets are UTC dates.

## Retention

Stats implements no retention, pruning, or partitioning. For long-lived servers, keeping the day tables permanently and defining a separate partitioning or retention policy for the hour tables — sized to actual query needs — is a reasonable approach, but it must be operated outside the plugin.
