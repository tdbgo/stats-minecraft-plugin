# Configuration reference

Describes the behavior of Stats 0.3.2. The current configuration version is `4`.

The live file is `plugins/Stats/config.yml`. It is created from the bundled template on first run, written by the plugin's I/O thread. Changes take effect on a server restart or `/stats reload`, never immediately on save.

## First run

- With the default `setup.enabled: false`, Stats opens no database connection, creates no schema, collects nothing, and uploads nothing. It logs a safe-mode warning and stops there.
- Review the database settings, set `setup.enabled: true`, then restart or run `/stats reload`.
- If reload initialization fails, the previously working runtime and its collection buffers are kept. The failure is reported to the command sender and the log.

## Database

| Key | Default | Range and behavior |
| --- | --- | --- |
| `database.type` | `sqlite` | `sqlite`, `postgres`, `mysql`. Also accepts `sqlite3`; `postgresql`, `pg`; `mariadb`, `maria`. An unrecognized value fails startup — there is no silent fallback to SQLite. |
| `database.tablePrefix` | `mstats_` | Must start with a letter or underscore and contain only letters, digits, and underscores. A missing trailing `_` is appended. Maximum 24 characters after that. A violation fails activation. |
| `database.queryTimeoutSeconds` | `30` | 1–300. Applied as the JDBC query timeout and, for remote engines, as the socket timeout. Connection acquisition uses the separate pool timeout below. |

### SQLite

| Key | Default | Notes |
| --- | --- | --- |
| `database.sqlite.file` | `stats.db` | Resolved inside the plugin data folder. |
| `database.sqlite.pragmas.journal_mode` | `WAL` | Passed to the driver as a data source property. |
| `database.sqlite.pragmas.synchronous` | `NORMAL` | |
| `database.sqlite.pragmas.foreign_keys` | `ON` | |
| `database.sqlite.busyTimeoutMs` | `5000` | Lock wait before the driver gives up. |

### Remote engines

| Key | Default | Notes |
| --- | --- | --- |
| `database.host` | `127.0.0.1` | |
| `database.port` | `5432` | **The shipped value is the PostgreSQL port.** MySQL and MariaDB operators must set `3306` explicitly. The value present in the file always wins over the engine-specific code default. |
| `database.database` | `minecraft` | |
| `database.schema` | `public` | PostgreSQL only; applied as `currentSchema`. |
| `database.username` | `stats` | |
| `database.password` | `CHANGE_ME` | Never included in the durable spool storage identity, and never written to the log. |
| `database.ssl.enabled` | `false` | When `false`, PostgreSQL uses `sslmode=disable` and MySQL/MariaDB uses `useSSL=false`. |
| `database.ssl.mode` | `prefer` | PostgreSQL only: `disable`, `prefer`, `required`, `verify-ca`, `verify-full`. Used only when `ssl.enabled` is `true`. |

PostgreSQL connections are built from a directly constructed `PGSimpleDataSource` with `applicationName=Stats`. MySQL and MariaDB use a `jdbc:mariadb://` URL with the MariaDB Connector/J driver class.

## Connection pool

| Key | Default | Range and behavior |
| --- | --- | --- |
| `database.pool.maximumPoolSize` | `2` | Bounded to 1–32. Flush, ping, and reload I/O are serialized on a single executor, so a large pool gains little. |
| `database.pool.minimumIdle` | `0` | Bounded to 0 through the effective maximum. Avoids holding a remote connection while the server is idle. |
| `database.pool.connectionTimeoutMs` | `5000` | Also derives the driver-level connect timeout. |
| `database.pool.idleTimeoutMs` | `600000` | |
| `database.pool.maxLifetimeMs` | `1800000` | |

SQLite is forced to a maximum pool size of 1 and a minimum idle of 0 regardless of what these keys say.

## Schema management

| Key | Default | Behavior |
| --- | --- | --- |
| `database.migrations.autoCreateTables` | `true` | Creates missing tables and indexes on activation. When `false`, Stats assumes the DDL was applied externally and skips creation and verification entirely. |
| `database.migrations.autoMigrate` | `true` | Adds missing columns for supported older schemas. When `false`, a missing column fails activation instead. |

The current automatic migration covers the schema v2 teleport columns on the player hour and day tables. A database whose recorded `schema_version` is newer than the plugin supports fails activation.

## Collection and flushing

| Key | Default | Range and behavior |
| --- | --- | --- |
| `flush.intervalSeconds` | `300` | 10–86,400. An empty buffer completes without any JDBC call. |
| `flush.maxBatchRows` | `5000` | 1–100,000. Splits JDBC batch execution only; it does not change transaction boundaries. |
| `tick.intervalSeconds` | `5` | 1–60. Sampling period for playtime, AFK accounting, and travel distance. |
| `afk.thresholdSeconds` | `60` | 5–86,400. Idle time after the last activity signal before a player counts as AFK. |

Values outside the accepted range are clamped to the nearest bound rather than rejected.

## Administration commands

| Key | Default | Gates |
| --- | --- | --- |
| `commands.enabled` | `true` | All `/stats` subcommands, including `help` and `status`. |
| `commands.allowReload` | `true` | `/stats reload` |
| `commands.allowDbPing` | `true` | `/stats db ping` |
| `commands.allowForceFlush` | `true` | `/stats flush` |

Every administration command requires the `stats.admin` permission or operator status.

Setting `commands.enabled: false` also blocks `/stats reload`, so re-enabling it requires editing the file on disk and restarting the server.

## Keys that no longer exist

Version 4 removed the unimplemented `riskDetection.*`, `privacy.*`, and `logging.*` template keys. If they remain in an older file they are ignored.

Regardless of configuration, this collector does not store chat message bodies, IP addresses, coordinates, or inventory snapshots. Per-hour chat message and character *counts* are collected — see [DATA_CATALOG.md](DATA_CATALOG.md). Risk alerting is not implemented and is the responsibility of an external analysis layer.
