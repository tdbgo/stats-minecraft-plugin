# Database compatibility

Stats designs its schema and queries around PostgreSQL, and keeps to broadly portable SQL so the same logical model runs on SQLite and MySQL/MariaDB.

## 1. Reference schema

- Default and immediately usable: `sql/sqlite/schema.sql`
- Canonical DDL: `sql/postgres/schema.sql`
- Compatible DDL: `sql/mysql/schema.sql`
- Current logical schema version: `2`, including the `ingest_batch` idempotency ledger and the teleport aggregate columns

## 2. Engine differences

**SQLite (default)**

Runs immediately with no installation, account, or network. Suitable for initial adoption, testing, and small servers. Concurrent-write throughput and very long retention are its limits, so PostgreSQL is the recommended production target. The pool is fixed at a single connection in code.

**UUID**

| Engine | Type |
| --- | --- |
| PostgreSQL | `UUID` |
| MySQL/MariaDB | `BINARY(16)`, converted in the application |
| SQLite | `BLOB(16)` |

**Timestamp**

| Engine | Type |
| --- | --- |
| PostgreSQL | `TIMESTAMPTZ`, stored in UTC |
| MySQL/MariaDB | `DATETIME(3)`, normalized to UTC |
| SQLite | `TEXT`, UTC ISO-8601 |

**Upsert**

| Engine | Form |
| --- | --- |
| PostgreSQL | `INSERT ... ON CONFLICT (...) DO UPDATE` |
| MySQL/MariaDB | `INSERT ... ON DUPLICATE KEY UPDATE` |
| SQLite | `INSERT ... ON CONFLICT(...) DO UPDATE` |

**Flush idempotency**

All three engines claim `ingest_batch.batch_id` first, inside the same transaction as the fact upserts. An already-present `batch_id` is treated as success without reapplying the aggregates.

**Partitioning and long retention**

Possible on more than one engine, but PostgreSQL is the more flexible option for indefinite retention with partitioning. Stats itself implements neither.

## 3. Portability rules for contributors

- Reserved words and case: table and column names stay lowercase snake_case.
- Indexes and constraints: prefer composite primary and unique keys over engine-specific features.
- String lengths: bound player-name-like fields sensibly, but keep plugin and world names as `TEXT` for headroom.
- Table prefix: `database.tablePrefix` avoids collisions. The shipped DDL assumes the default `mstats_`.

## 4. Paper class loader notes

**PostgreSQL.** Connections do not rely on `driverClassName` or `DriverManager` registration. A `PGSimpleDataSource` is constructed directly in the plugin class loader and handed to the pool. The configuration meaning of the JDBC URL and the durable spool storage identity string are unchanged by this.

**MariaDB Connector/J.** From Stats 0.3.2 the connector is no longer included in the shaded JAR. Paper resolves the original Maven artifact through the `libraries` declaration in `plugin.yml` and adds it to the plugin class path. Existing MySQL and MariaDB settings, JDBC URLs, tables, and data semantics are unchanged.

Note that the `libraries` declaration is unconditional. It does not depend on `database.type`, so Paper may perform that resolution even on a SQLite or PostgreSQL server. A fully offline first startup requires the artifact to already be in Paper's library cache.
