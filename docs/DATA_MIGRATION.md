# Database migration design (proposal)

> **Not implemented.** This document is a design proposal. Stats 0.3.2 has **no** export, import, or migrate command. The implemented commands are `/stats help`, `reload`, `status`, `db ping`, `db health`, and `flush` — see [OPERATIONS.md](OPERATIONS.md). Everything below describes work that would have to be built, or a workflow an operator would perform with external tooling. Do not read any command name here as available.

Goal: allow a server that started on the default SQLite backend to move its data safely to PostgreSQL or MySQL during operation.

Core constraints:

- `dim_command` and `dim_command_variant` IDs can differ between databases, so any export must be keyed on `command_key` and `variant_key`, never on `command_id` or `variant_id`.
- `/stats reload` can switch the database type today, but it moves no data. A separate procedure is required.

## 1. Proposed operational flow (SQLite to PostgreSQL)

1. Run `/stats flush` to push pending aggregates into the source database.
2. Pause collection and flushing if an operational mode for it exists.
3. Produce an export file from the source database.
4. Set `database.type: postgres` and the connection settings in `config.yml`.
5. Run `/stats reload` to connect to the target and create the schema.
6. Load the export into the target database.
7. Confirm with `/stats status` that collection and flushing resumed normally.

Steps 3 and 6 have no plugin support today and would need external tooling.

### 1.1 Table prefix

- An export file would be defined against the logical schema — `dim_player`, `fact_player_day` — and would therefore be prefix independent.
- An import would create and upsert under whatever `database.tablePrefix` is currently configured.
  - Keeping the same prefix as the source causes the least confusion.
  - Moving to a different prefix is possible, but then two sets coexist in the same database.

## 2. Proposed command surface (draft specification)

> None of these commands exist.

- `/stats export <path> [--days N] [--gzip] [--format jsonl]`
  - Default `jsonl` (NDJSON), gzip recommended
  - `--days N` limits to the most recent N days to save size and time
  - Secrets must never be printed during export
- `/stats import <path> [--mode merge] [--dry-run]`
  - `merge` upserts additively into existing data; recommended default
  - `dry-run` reports only the expected row count, size, and duration
- `/stats migrate <target>` (optional)
  - A guided wizard around export, reload, and import
  - Two-step confirmation to prevent accidents

## 3. Proposed export format (engine independent)

**File layout**

- `stats-export-manifest.json`
  - `export_version`: 1
  - `plugin_version`, `schema_version`
  - `created_at` in UTC
  - `range`: optional day range
  - Optional file list and hashes
- `players.jsonl`
- `sessions.jsonl`
- `player_hour.jsonl`, `player_day.jsonl`
- `command_hour.jsonl`, `command_day.jsonl`
- `block_group_day.jsonl`
- `death_day.jsonl`

**Record conventions**

- Times as UTC ISO-8601 strings
- UUIDs as canonical strings, `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`
- Commands as `command_key` plus `variant_key`, empty string when absent

**Examples**

```json
{ "player_uuid": "...", "hour_ts": "2025-01-01T10:00:00Z", "command_key": "worldedit:replace", "variant_key": "material=stone", "count": 3 }
{ "player_uuid": "...", "day": "2025-01-01", "playtime_sec": 3600, "sessions": 2 }
```

## 4. Proposed import upsert strategy

- `dim_player`: upsert on UUID
- `dim_command`: upsert on `command_key`, then read back `command_id`
- `dim_command_variant`: upsert on `(command_id, variant_key)`, then read back `variant_id`
- Fact tables: upsert on the primary key; in merge mode, `count = count + excluded.count`

## 5. Consistency considerations

- Force a flush before export so the source database is current.
- Pausing collection and flushing during export would be safest if such a mode existed.
- An import should run as an asynchronous batch and report progress, rate, and estimated remaining time.

## 6. Long-retention servers

- Export files can become very large on long-lived servers, so a day-bounded export should be the default.
- A full export is better performed as a series of monthly or quarterly snapshots.
