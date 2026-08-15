# Backfilling from existing plugin data (assessment)

> **Not implemented.** Stats 0.3.2 has no import, backfill, or third-party-plugin integration. Nothing described here happens automatically. This document assesses what a manual, externally tooled backfill could recover, and what it could not. It is a feasibility study, not a feature description.

Question: if Stats is adopted today, can historical data already collected by other plugins be loaded into the Stats database?

Answer: partly. How much depends entirely on what the earlier plugin recorded. Stats' own collection scope — commands, blocks, minute-level activity — cannot be recovered retroactively unless an equivalent record already exists.

## 1. Candidate sources

These are the plugin data stores commonly found on a Paper server. Paths are the conventional defaults; substitute your own.

| Source | Typical location | Format |
| --- | --- | --- |
| Plan (Player Analytics) | `plugins/<Plan>/database.db` | SQLite |
| CoreProtect | `plugins/<CoreProtect>/database.db` | SQLite, often large |
| FastAsyncWorldEdit history | `plugins/<FAWE>/history/**/summary.db` | SQLite |
| WorldGuard UUID cache | `plugins/<WorldGuard>/cache/profiles.sqlite` | SQLite |
| Others | varies | LuckPerms (H2), Essentials (YAML), and similar |

Table and column names below are indicative. Verify them against the actual version installed on your server before relying on any of it.

## 2. What each source can and cannot provide

### A. Plan (Player Analytics)

Representative tables: user records, session records with mob kills, deaths and AFK time, per-world time by game mode, TPS samples, and ping statistics.

Transferable to Stats:

- `mstats_dim_player` — UUID, name, first observation
- `mstats_fact_session` — join, quit, duration, AFK; world naming is limited from Plan alone
- `mstats_fact_player_day` — playtime, session count, deaths, and mob kills derived by rolling sessions up per day. Daily AFK is not stored in this table; AFK remains on the imported session rows.

Limits: Plan does not generally record block place and break or command usage as source events, so Stats' core collection cannot be reconstructed from it.

### B. CoreProtect

Representative tables: user records, block change logs with time, user, world, coordinates, type and action, container inventory changes, and — depending on server configuration — command and chat logs.

Transferable, on the assumption of conversion to aggregates:

- Block activity rolled up into hourly totals in `mstats_fact_player_hour` and daily material/action groups in `mstats_fact_block_group_day`
- Grief-related signals such as container break and change spikes, as aggregates only
- Where command or chat logs exist, partial reconstruction of `mstats_fact_command_day`/`_hour` and the chat counters

Limits and cautions: CoreProtect records coordinates and detailed block information. Any transfer into Stats should store aggregates only and discard the detail, to stay consistent with the Stats collection policy. These databases can be very large, so a full scan is slow; stage the work by recent days or months.

### C. FastAsyncWorldEdit history

Representative table: edit records with player UUID, time, bounds, size, and the command string.

Transferable:

- WorldEdit and FAWE command usage, by normalizing the command string with the [COMMAND_NORMALIZATION.md](COMMAND_NORMALIZATION.md) rules into `mstats_fact_command_day`/`_hour`
- Optionally, edit size, if an extension table were added for large-edit reporting

Limits: covers WorldEdit-family activity only, not general command or play activity.

### D. WorldGuard UUID cache

Transferable: a UUID-to-name mapping usable to improve `mstats_dim_player.last_known_name`.

## 3. What cannot be fully reconstructed

- Minute-level activity such as `active_minutes`. Without the original per-minute events, only a session-based approximation is possible.
- Full normalized command statistics. Outside the WorldEdit family, absent historical logs mean absent data.

## 4. A realistic staged approach

1. Backfill sessions, playtime, and AFK from Plan, recovering the baseline dashboard KPIs.
2. Backfill block activity and grief signals from CoreProtect as daily rollups, to limit size and time.
3. Backfill WorldEdit command usage and large edits from FAWE history.
4. From then on, let Stats perform the primary collection, where command, block, and activity precision is its responsibility.

## 5. If this were to be built

- A converter from each external source into the export format proposed in [DATA_MIGRATION.md](DATA_MIGRATION.md), as a standalone script or as a subcommand.
- Staged transfer for CoreProtect and FAWE, starting from the most recent N days, to manage server load.

Both items remain proposals. Neither exists in Stats 0.3.2.
