# Storage estimate

> **Approximate figures.** Every number here is a rough order-of-magnitude estimate, not a measurement or a guarantee. Real sizes depend on the engine, index layout, fill factor, and activity mix. Measure your own deployment before committing to a capacity plan.

Stats does not store an unbounded raw event stream. It stores session rows plus hour and day bucket aggregates, as described in [SCHEMA.md](SCHEMA.md). Storage therefore scales roughly with **active users × active hours × command diversity**.

## 1. The dominant variables

- `fact_command_hour` and `fact_command_day` — command usage grows in cardinality most easily, especially on servers with many plugins.
  - Normalizing through `dim_command` and `dim_command_variant` keeps the fact tables on integer keys.
  - Argument capture is limited to an allow list and effectively a single argument. See [COMMAND_NORMALIZATION.md](COMMAND_NORMALIZATION.md).
- `fact_player_hour` — only hours in which the player is connected or produces activity create rows. A continuously connected player can therefore produce up to 24 rows in a UTC day; inactive hours create none.

## 2. Approximate daily growth

Symbols:

- `DAU` — daily active users
- `H` — active hours per user per day, so two hours average means `H=2`
- `Uch` — distinct command variants a user touches within one active hour

Approximate row counts:

| Table | Rows per day |
| --- | --- |
| `fact_player_hour` | ≈ `DAU × H` |
| `fact_player_day` | ≈ `DAU` |
| `fact_command_hour` | ≈ `DAU × H × Uch` |
| `fact_command_day` | ≈ `DAU × Ucd`, where `Ucd` is distinct daily variants and is usually below `H × Uch` |
| `ingest_batch` | Scheduled flushes contribute up to `86400 / flush.intervalSeconds` rows when every interval is non-empty: 288 at the 300-second default. Manual, reload, and shutdown flushes can add more. |

Approximate row sizes including indexes:

| Table | Bytes per row |
| --- | --- |
| `fact_player_hour` | 200–400 |
| `fact_player_day` | 150–300 |
| `fact_command_hour` | 120–300 with integer foreign keys — storing `command_key` directly as `VARCHAR` would be substantially larger because of the index |
| `ingest_batch` | 50–150, so roughly 5–16 MB per year as a small ledger |

## 3. Illustrative scenarios

These are worked examples of the formula above, not benchmarks.

**Small server** — `DAU=100`, `H=2`, `Uch=8`

- `fact_player_hour`: 200 rows/day → about 40–80 KB/day
- `fact_command_hour`: 1,600 rows/day → about 0.2–0.5 MB/day
- Main tables combined: roughly **0.3–0.7 MB/day**, **9–21 MB/month**, **0.1–0.3 GB/year**

**Medium server** — `DAU=500`, `H=3`, `Uch=12`

- `fact_player_hour`: 1,500 rows/day → about 0.3–0.6 MB/day
- `fact_command_hour`: 18,000 rows/day → about 2–5 MB/day
- Combined: roughly **3–7 MB/day**, **0.1–0.2 GB/month**, **1–3 GB/year**

**Large server with high command diversity** — `DAU=2,000`, `H=3`, `Uch=25`

- `fact_player_hour`: 6,000 rows/day → about 1–2.5 MB/day
- `fact_command_hour`: 150,000 rows/day → about 18–45 MB/day
- Combined: roughly **20–50 MB/day**, **0.6–1.5 GB/month**, **7–18 GB/year**

## 4. Keeping indefinite retention practical

> Operational guidance for your database, not plugin features. Stats implements no retention, pruning, or partitioning.

- Partition by month, particularly `fact_command_hour`.
- Keep the day rollups (`fact_command_day`, `fact_player_day`) permanently, and re-examine whether the hour tables are needed at the same horizon.
- If "indefinite" means "never delete," plan at minimum to compress or archive old hour partitions.

## 5. Local spool size

The spool holds undelivered aggregate snapshots, not raw events, so it stays small.

- Snapshot size depends on the active player/hour/day keys, command variants, block groups, death causes, identities, and completed sessions present in that interval. It is not bounded by `DAU × Uch` alone and has no few-megabyte guarantee.
- In steady state a spool file is deleted right after commit, so usually less than one flush of data is on disk. During a database outage, the immutable snapshots awaiting retry accumulate instead.
- Spool payloads are validated against a 64 MiB limit and contain no raw events. A snapshot exceeding that limit fails before transmission and is kept in memory.

## 6. Difference against raw event logging

- Raw block logging adds a row per break or place, whereas this structure merges the same `player/day/group/action` into one row.
- Logging position once per second could produce 86,400 points per player per day; Stats discards coordinates and keeps a single integer `distance_m` per active hour.
- Command logging is merged the same way, one row per `player/hour/variant`.

On build-heavy or travel-heavy servers the gap against raw logs can therefore reach one to three orders of magnitude. The actual ratio depends on event frequency and the number of distinct command variants.
