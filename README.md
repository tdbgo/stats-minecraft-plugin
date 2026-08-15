# Stats

**Stats by PLAYCITY BLOCK** — release `0.3.2`

[한국어 문서](README.ko.md) · [Repository](https://github.com/tdbgo/stats-minecraft-plugin) · [Issues](https://github.com/tdbgo/stats-minecraft-plugin/issues) · [Release v0.3.2](https://github.com/tdbgo/stats-minecraft-plugin/releases/tag/v0.3.2)

Stats is a server-only Paper plugin that records what your players do, in a form you can query. It is not a dashboard, a moderation tool, or a raw event log. It collects a fixed, documented set of player activity counters and writes them to a database you control, so you can build reports on top of them with ordinary SQL.

The design goal is a collector you can leave running and forget about. Events are counted in memory. A single dedicated I/O thread writes the accumulated batch to the database on a schedule — 300 seconds by default — instead of writing once per event. When there is nothing to write, no database connection is requested at all.

Data volume is bounded on purpose. Commands are normalized to a canonical key plus a short allow-listed variant, block materials collapse into about two dozen groups, and per-player rows are keyed by hour or by day. Repeated block events with the same player, day, material group, and action merge into one row instead of producing one row per event, and no coordinates are kept.

Delivery is built to survive database outages. Every non-empty batch gets a UUID, is written to a checksummed local spool file before transmission, and is claimed in an `ingest_batch` ledger inside the same transaction as the fact rows. A failed flush is retried with the same batch id — on the next flush or on the next server start — without double-counting.

---

## 1. Requirements

| Item | Requirement |
| --- | --- |
| Server | Paper, Minecraft/Paper `26.2` |
| Verified build | Paper 26.2 build 112 (compile and test target) |
| Java | 25 |
| Side | Server only — clients install nothing |
| Storage | SQLite (built in), or PostgreSQL, or MySQL/MariaDB |

The plugin descriptor declares `api-version: "26.2"`, and the project builds against Paper API `26.2.build.112-stable`. Other builds inside the 26.2 line are not verified by this repository.

MySQL and MariaDB are reached through MariaDB Connector/J, which Paper resolves separately. See [Network behavior](#63-network-behavior).

## 2. Installation

1. Stop the server.
2. Copy `Stats-0.3.2.jar` into the Paper `plugins` directory.
3. Start the server once. Stats writes `plugins/Stats/config.yml` and `plugins/Stats/command-aliases.yml`, then stops there. Nothing is collected yet and no database is contacted, because `setup.enabled` is `false`.
4. Edit `plugins/Stats/config.yml`.
5. Set `setup.enabled: true`, then restart the server or run `/stats reload`.

Step 3 is deliberate. The plugin logs `Stats is in safe setup mode (setup.enabled=false).` on that first start. That warning means installation worked.

The first start may also need Maven Central access so Paper can resolve the declared database library. This applies to every backend, not only MySQL. See [Network behavior](#63-network-behavior).

## 3. Quick start (about five minutes)

This path uses the built-in SQLite backend, which needs no database server, no account, and no database network access.

1. Install the JAR and start the server once, as above.
2. Open `plugins/Stats/config.yml` and change one line:

   ```yaml
   setup:
     enabled: true
   ```

   Leave `database.type: sqlite` as it is.
3. Run `/stats reload` in the console or as an operator. The console logs `Stats enabled. DB=sqlite prefix=mstats_`.
4. Verify:

   ```text
   /stats status
   /stats db ping
   ```

   `status` should report `active=true` and the database type. `db ping` should return an `OK` line with a latency in milliseconds.
5. Play for a minute — move, break a block, run a command — then force a write:

   ```text
   /stats flush
   ```

   You should see `Stats: flush done (rows=N)`.
6. The database file is `plugins/Stats/stats.db`. Query it with any SQLite client:

   ```sql
   SELECT last_known_name, first_seen_at, last_seen_at FROM mstats_dim_player;
   SELECT day, playtime_sec, sessions, deaths FROM mstats_fact_player_day;
   ```

To move to PostgreSQL or MySQL/MariaDB later, change `database.type` and the connection settings, then reload. Switching type does not migrate existing rows — see [Upgrades and compatibility](#7-upgrades-and-compatibility).

## 4. Core usage

Once `setup.enabled` is `true`, Stats runs without further intervention.

**What happens on a running server**

- A repeating task, every `tick.intervalSeconds` (default 5), accounts playtime, AFK time, and sampled travel distance for each online player.
- Paper events add counters for chat, commands, block place and break, deaths, kills, teleports, joins, quits, and world changes.
- A player becomes AFK once `afk.thresholdSeconds` (default 60) have passed since their last activity signal. Movement across a block boundary, chat, a command, a block place or break, and a teleport all count as activity. Looking around does not.
- Every `flush.intervalSeconds` (default 300), the accumulated counters are drained, spooled to disk, and written to the database in one transaction.
- If the buffer is empty, the flush ends without opening a database connection.

**Where the data lands**

Rows are written under your configured table prefix (`mstats_` by default):

| Table | Grain |
| --- | --- |
| `mstats_dim_player` | one row per player |
| `mstats_fact_session` | one row per completed session |
| `mstats_fact_player_hour` | player × hour |
| `mstats_fact_player_day` | player × day |
| `mstats_fact_command_hour` / `_day` | player × bucket × command variant |
| `mstats_fact_block_group_day` | player × day × material group × place/break |
| `mstats_fact_death_day` | player × day × damage cause |
| `mstats_dim_command`, `mstats_dim_command_variant` | command dimension |
| `mstats_meta`, `mstats_ingest_batch` | schema metadata and the idempotency ledger |

Hour buckets are UTC hour starts; day buckets are UTC dates. The full column list is in [docs/SCHEMA.md](docs/SCHEMA.md), and exact metric definitions are in [docs/DATA_CATALOG.md](docs/DATA_CATALOG.md).

Stats provides no in-game reports, leaderboards, or web UI. Reporting is your query layer's job; [docs/ANALYTICS_IDEAS.md](docs/ANALYTICS_IDEAS.md) lists metrics the stored schema can support.

## 5. Commands and permissions

There is one command, `/stats`, and one permission, `stats.admin`.

| Permission | Default | Grants |
| --- | --- | --- |
| `stats.admin` | operators (`default: op`) | every `/stats` subcommand |

A sender passes the check with `stats.admin` **or** operator status. Console always qualifies.

| Command | Effect | Config gate |
| --- | --- | --- |
| `/stats` or `/stats help` | Prints the subcommand list. | `commands.enabled` |
| `/stats reload` | Re-reads `config.yml` and `command-aliases.yml`, builds a new runtime on the I/O thread, and swaps it in only after it is fully ready. On failure the previous runtime keeps running. | `commands.allowReload` |
| `/stats status` | Prints version, `active`/`initializing`, database type and table prefix, pending row count, durable pending batch count, retired runtime count, and the last reload, flush attempt, flush success, and flush error. | `commands.enabled` |
| `/stats db ping` | Runs `SELECT 1` on the I/O thread and reports round-trip milliseconds. | `commands.allowDbPing` |
| `/stats db health` | Prints connection pool counters: active, idle, total, awaiting. Runs no SQL. | `commands.enabled` |
| `/stats flush` | Queues an immediate asynchronous flush. Reports `nothing to flush` when the buffer is empty, and refuses to stack duplicate requests. | `commands.allowForceFlush` |

`db ping`, `db health`, and `flush` require an active runtime. In safe setup mode they answer `Stats: inactive.`

There are no import, export, query, or migration subcommands.

## 6. Configuration, storage, and network behavior

The live file is `plugins/Stats/config.yml`. Current `config-version` is `4`. [docs/CONFIG.md](docs/CONFIG.md) documents every key, its default, validation, and runtime behavior.

### 6.1 Key settings

```yaml
config-version: 4

setup:
  enabled: false          # nothing runs until this is true

database:
  type: sqlite            # sqlite | postgres | mysql
  tablePrefix: "mstats_"
  queryTimeoutSeconds: 30
  host: "127.0.0.1"
  port: 5432              # use 3306 for MySQL/MariaDB
  database: "minecraft"
  schema: "public"        # PostgreSQL only
  username: "stats"
  password: "CHANGE_ME"
  pool:
    maximumPoolSize: 2
    minimumIdle: 0
  migrations:
    autoCreateTables: true
    autoMigrate: true

flush:
  intervalSeconds: 300
  maxBatchRows: 5000

tick:
  intervalSeconds: 5

afk:
  thresholdSeconds: 60
```

Notes that matter in practice:

- `database.type` accepts `sqlite` (also `sqlite3`), `postgres` (also `postgresql`, `pg`), and `mysql` (also `mariadb`, `maria`). An unrecognized value fails startup rather than falling back to SQLite.
- The shipped `port` is `5432`. MySQL and MariaDB operators must set `3306` explicitly. The value present in the file always wins over the engine-specific code default, so leaving `5432` in place will point a MySQL configuration at the wrong port.
- `database.tablePrefix` must start with a letter or underscore, contain only letters, digits, and underscores, and stay within 24 characters. A missing trailing `_` is appended. Violations fail activation.
- The remote pool defaults to `maximumPoolSize: 2` and `minimumIdle: 0`, because flushes are serialized on one thread and idle connections are not wanted. SQLite is forced to a single connection regardless of the configured values.
- Editing `config.yml` on disk has no effect until a restart or `/stats reload`.

### 6.2 Storage

- **SQLite** — file `plugins/Stats/stats.db`, WAL journal, no network. Fine for a first deployment or a small server. Concurrent-write throughput and very long retention are its known limits.
- **PostgreSQL** — the canonical target. Connections are built from a `PGSimpleDataSource` with `applicationName=Stats` and your configured `currentSchema`.
- **MySQL / MariaDB** — reached over a `jdbc:mariadb://` URL using MariaDB Connector/J.

All three share the same logical schema, at schema version `2`. Static DDL lives in `sql/sqlite/schema.sql`, `sql/postgres/schema.sql`, and `sql/mysql/schema.sql`. Engine differences are covered in [docs/DB_COMPAT.md](docs/DB_COMPAT.md).

Undelivered batches are held in `plugins/Stats/spool/<storage-id>/`, where `<storage-id>` is a hash of the database type, table prefix, and JDBC target. Passwords are not part of that identity. Spool files carry a CRC32 checksum and are moved into place atomically; a corrupt file fails activation instead of being deleted. See [docs/FLUSHING.md](docs/FLUSHING.md).

### 6.3 Network behavior

Stats itself has no telemetry, no update checker, no metrics reporter, and no HTTP endpoint of any kind. The only connection it opens is the JDBC connection to the database you configured. With `database.type: sqlite`, it opens no network connection at all.

One separate case exists, and it belongs to Paper rather than to the plugin. `plugin.yml` declares `org.mariadb.jdbc:mariadb-java-client:3.5.2` as a Paper library. MariaDB Connector/J is **not** shaded into `Stats-0.3.2.jar` — the build fails if it is. Paper resolves that coordinate from Maven Central and caches the JAR in its own library store.

What this means concretely:

- The declaration is unconditional. It does not depend on `database.type`, so a SQLite or PostgreSQL server is subject to the same resolution step.
- A server whose Paper library cache already holds that exact artifact starts without a download.
- A server without it needs Maven Central reachable on that start.
- Once the artifact is cached, later starts do not download it again.

Do not treat a fresh, fully offline installation as supported unless that artifact is already present in Paper's library cache.

## 7. Upgrades and compatibility

**Upgrading to 0.3.2**

1. Stop the server normally. Do not use hot-reload tooling.
2. Back up the database.
3. Replace the old JAR with `Stats-0.3.2.jar`.
4. Start the server and read the log for schema migration and durable batch recovery lines.
5. Check `/stats status` and `/stats db ping`.
6. Generate a little activity, run `/stats flush`, and confirm the fact tables grew.

**Compatibility of 0.3.2**

- `config-version` stays `4`. Schema version stays `2`.
- Table names, columns, commands, permissions, and collection behavior are unchanged from 0.3.1.
- Existing SQLite, PostgreSQL, MySQL, and MariaDB configurations remain valid.
- The only operational change is that MariaDB Connector/J now arrives as a Paper library instead of being bundled.

**Schema and config migration**

- With `autoCreateTables: true`, missing tables and indexes are created on activation.
- With `autoMigrate: true`, a schema-v1 database gains the four teleport columns via `ALTER TABLE`. With `autoMigrate: false`, a missing column fails activation instead.
- A database whose recorded `schema_version` is newer than the plugin supports fails activation.
- Config files are **not** rewritten automatically. Missing keys fall back to defaults in memory only; the file on disk is left alone. Config versions 1 through 4 are readable. See [docs/CONFIG_MIGRATION.md](docs/CONFIG_MIGRATION.md).
- `plugins/Stats/command-aliases.yml` is written once and never overwritten by an upgrade. New canonical rules from a later release must be merged by hand.

**Changing database backend**

`/stats reload` can point Stats at a different database, but it moves no data. The retired runtime finishes writing its pending batches to the old target, then closes. Copying history across engines needs external tooling; [docs/DATA_MIGRATION.md](docs/DATA_MIGRATION.md) is a design proposal for that work, not a description of a shipped feature.

## 8. Limitations and deliberately uncollected data

### 8.1 What is stored

- **Player identity** — UUID, first and last seen timestamps, and `last_known_name` for convenience.
- **Sessions** — join and quit times, duration, AFK seconds, join world, quit world.
- **Playtime and AFK** — seconds per player-hour; playtime is also rolled up per player-day.
- **Active minutes** — a per-hour count from 0 to 60, derived from a minute bitset.
- **Chat volume** — message count and total character count per player-hour.
- **Commands** — execution counts per canonical command key and variant, per hour and per day.
- **Blocks** — placed and broken totals per player-hour, plus per-day counts by material group and action.
- **Deaths and kills** — death counts per damage cause per day, and PvP versus mob kill counts per day.
- **Travel** — sampled distance in whole metres per player-hour.
- **Teleports** — count and same-world distance, per hour and per day.

### 8.2 Never stored

- **Coordinates.** Positions are read in memory to compute a distance and are discarded immediately. No X/Y/Z, no path history, no per-event location.
- **Chat text.** Message bodies are never written to the spool or the database. Only the per-hour message count and character count described above are kept.
- **Raw command lines and arguments.** Only a canonical command key and an allow-listed variant survive normalization. Target player names, message bodies, and coordinate arguments are dropped.
- **IP addresses.** Never read, hashed, or stored. `mstats_fact_session.ip_hash` exists for schema compatibility and is not written by this collector.
- **Client brand and locale.** Same situation: the `client_brand` and `locale` columns exist and are not written.
- **Inventory snapshots**, and item pickup, drop, or craft events.
- **Individual block events.** Neither per-event timestamps and positions nor the original `Material` name are kept — only a daily count per material group.

### 8.3 Not implemented

- Per-world dwell time and dimension keys.
- Damage dealt and taken.
- Server health sampling such as TPS or MSPT.
- Risk detection, alerting, or automatic moderation. [docs/RISK_DETECTION.md](docs/RISK_DETECTION.md) is a design proposal for an external analysis layer.
- Opt-in chat or IP collection.
- A raw event stream or a per-event write-ahead log.
- Retention, pruning, or partitioning. `mstats_ingest_batch` grows by at most one row per non-empty flush and is never trimmed automatically.

### 8.4 Accuracy limits

- Travel distance is a sum of straight lines between position samples taken every `tick.intervalSeconds`. Curved and back-and-forth movement measures short. Lowering the interval improves accuracy and raises tick cost.
- A single movement sample is counted only when it is greater than 0 and strictly less than 1000 metres; a sample of exactly 1000 metres is excluded. Cross-world movement contributes 0. Teleports are counted separately, and a cross-world teleport contributes distance 0.
- `playtime_sec` is total connected time; `afk_sec` is the AFK portion *inside* it. They overlap — do not add them together.
- `/stats reload` closes open sessions and starts new ones, so a reload can split one visit into two `fact_session` rows.
- Memory counters not yet drained can be lost if the process is killed between flushes. A normal shutdown closes sessions and attempts a final flush; if the database is unreachable, that batch stays in the spool for the next start.

## 9. Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Log says `Stats is in safe setup mode (setup.enabled=false).` | Expected before setup. Set `setup.enabled: true`, then restart or `/stats reload`. |
| `/stats` answers `Stats: no permission.` | The sender has neither `stats.admin` nor operator status. |
| `/stats` answers `Stats: commands are disabled in config.` | `commands.enabled` is `false`. Because `/stats reload` is gated too, edit `config.yml` on disk and restart. |
| `/stats status` shows `active=false` | Either safe setup mode, or the last initialization failed. Check `lastFlushError` and the server log. |
| Reload reports `previous runtime kept` | The new configuration failed to initialize — an unsupported `database.type`, an invalid `tablePrefix`, an unreachable host, a bad credential, or a syntax error in `command-aliases.yml`. The old runtime is still collecting. Fix the file and reload again. |
| `Unsupported config-version` | The file declares a version below 1 or above 4. |
| Activation fails with `Schema migration required: missing ...` | `autoMigrate` is `false` against an older schema. Apply the DDL yourself or set `autoMigrate: true`. |
| Activation fails on `Database schema version N is newer than supported version 2` | The database was written by a newer Stats. Upgrade the plugin instead of downgrading the database. |
| Startup cannot find `org.mariadb.jdbc.Driver` | Paper could not resolve the declared library. Restore Maven Central access for one start, or seed Paper's library cache with that artifact. |
| `/stats flush` says `flush failed; batch retained` | The batch is kept in memory and in the spool. Repair the database, then `/stats flush` again, or restart — recovery replays it with the same `batch_id`, so nothing is double-counted. |
| `/stats status` shows a rising `durablePendingBatches` | Writes are failing repeatedly. Check `lastFlushError`, connectivity, credentials, and disk space. |
| Activation fails with a spool checksum or format error | A spool file is damaged. It is preserved on purpose. Copy `plugins/Stats/spool/` and the log before touching anything. |
| `/stats db ping` fails but the host is up | Check `queryTimeoutSeconds`, `pool.connectionTimeoutMs`, TLS settings, and that the port matches the engine — `5432` for PostgreSQL, `3306` for MySQL/MariaDB. |
| Counts look low for a busy server | Nothing is written before a flush completes. Wait for the interval, or run `/stats flush`. |

Operational procedures in depth are in [docs/OPERATIONS.md](docs/OPERATIONS.md).

## 10. Support and license

- Source: <https://github.com/tdbgo/stats-minecraft-plugin>
- Issues: <https://github.com/tdbgo/stats-minecraft-plugin/issues>
- Release 0.3.2: <https://github.com/tdbgo/stats-minecraft-plugin/releases/tag/v0.3.2>

When reporting a problem, include the Stats version, the Paper build, the Java version, `database.type`, and the `/stats status` output. Never paste your `config.yml` without removing the password.

### Documentation map

**Current behavior**

| Document | Purpose |
| --- | --- |
| [docs/CONFIG.md](docs/CONFIG.md) | Every configuration key, default, and range |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Day-to-day operation, failure handling, upgrade checklist |
| [docs/DATA_CATALOG.md](docs/DATA_CATALOG.md) | Exactly what is and is not collected |
| [docs/SCHEMA.md](docs/SCHEMA.md) | Table and column reference for schema v2 |
| [docs/FLUSHING.md](docs/FLUSHING.md) | Aggregation, batching, durability, idempotency |
| [docs/COMMAND_NORMALIZATION.md](docs/COMMAND_NORMALIZATION.md) | How command keys and variants are derived |
| [docs/DB_COMPAT.md](docs/DB_COMPAT.md) | Engine differences and portability rules |
| [docs/CONFIG_MIGRATION.md](docs/CONFIG_MIGRATION.md) | Config version history and upgrade behavior |
| [docs/STORAGE_ESTIMATE.md](docs/STORAGE_ESTIMATE.md) | Approximate sizing model |
| [CHANGELOG.md](CHANGELOG.md) | Release index for 0.3.0 – 0.3.2 |
| [docs/MODRINTH.md](docs/MODRINTH.md) | Project-listing preparation reference |

**Design proposals — not shipped features.** These describe workflows and tooling that do not exist in the plugin. Read them as plans:
[docs/DATA_MIGRATION.md](docs/DATA_MIGRATION.md),
[docs/IMPORT_EXISTING_DATA.md](docs/IMPORT_EXISTING_DATA.md),
[docs/PG_BACKFILL.md](docs/PG_BACKFILL.md),
[docs/ANALYTICS_IDEAS.md](docs/ANALYTICS_IDEAS.md),
[docs/RISK_DETECTION.md](docs/RISK_DETECTION.md).

### Building from source

```text
./gradlew clean test build
```

The build uses the Gradle 9.6.1 wrapper and a Java 25 toolchain. Output is `build/libs/Stats-0.3.2.jar`. The `check` task also runs `verifyDistribution`, which asserts that the plugin class is present, that MariaDB Connector/J and the Paper/Bukkit API are absent, and that every required license notice is packaged.

### License

Project-authored source, tests, documentation, and resources are licensed under the [MIT License](LICENSE). `PLAYCITY` is the legal copyright holder.

Third-party components keep their own licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the [licenses](licenses) directory. HikariCP, SQLite JDBC, and the PostgreSQL JDBC driver are bundled with their notices. MariaDB Connector/J (LGPL-2.1-or-later) is not included in the Stats JAR; Paper resolves it separately.
