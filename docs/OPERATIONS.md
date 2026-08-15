# Operations

Every command requires the `stats.admin` permission or operator status, and is subject to `commands.enabled` and the individual `allow*` settings.

## First activation

1. Start the server once to create `plugins/Stats/config.yml` and `plugins/Stats/command-aliases.yml`.
2. Review the database settings and set `setup.enabled: true`.
3. Restart the server or run `/stats reload`.
4. Confirm with `/stats status`, `/stats db ping`, and `/stats db health`.

In safe mode, no database connection is created at all.

## Implemented commands

- `/stats reload`
  - Reads the config and alias files, validates them, and initializes the connection pool and schema on the `Stats-IO` thread.
  - Swaps to the new runtime on the main thread only once the candidate is fully ready.
  - On failure, the existing runtime continues.
  - Existing buffers and already-failed batches keep flushing to their original database from a retired queue.
  - Changing the database type or prefix is not a data migration. The previous context first writes its remaining batches to the previous database, then closes.
- `/stats status`
  - Plugin version, `active`/`initializing`, database type and prefix, current pending row count, durable pending batch count, retired runtime count, and the most recent reload, flush attempt, flush success, and flush error.
- `/stats db ping`
  - Executes `SELECT 1` on the I/O executor and prints the latency.
- `/stats db health`
  - Prints the connection pool's active, idle, total, and awaiting counts. Executes no SQL.
- `/stats flush`
  - Schedules an immediate asynchronous flush. It does not queue a duplicate if one is already running or pending.

Arbitrary SQL, export, import, test-write, and migration query commands are not implemented.

## Verifying idle behavior

With no players online and no events, only the local timer and executor run every 300 seconds; no JDBC connection is requested. To avoid holding a remote connection while idle, use the version 4 defaults:

```yaml
database:
  pool:
    maximumPoolSize: 2
    minimumIdle: 0
```

An older configuration that explicitly specifies `10` and `2` is treated as a deliberate operator setting and is not overwritten. Change it by hand to adopt the low-idle behavior.

## Failures and shutdown

- On a flush failure, check `lastFlushError` in `/stats status` and the server log.
- Failed batches are retained in memory and in `plugins/Stats/spool/<storage-id>/`, so after the database recovers you can retry immediately with `/stats flush`, or let the next startup replay them automatically.
- Retrying the same snapshot cannot double-count, because `ingest_batch.batch_id` guards it.
- A normal plugin disable closes online sessions and schedules a final asynchronous flush. If the database is down but the spool write succeeds, that final batch stays in the spool. If both operations fail, the plugin logs that the batch may be lost.
- A corrupt spool file or checksum error preserves the file and fails activation. Secure a copy of the file and the server log before deleting anything.
- An active memory buffer not yet drained into a snapshot can be lost on a forced process kill.

## Upgrade checklist

1. Prepare Java 25 and Paper `26.2` build 112, and review your current `config-version` against the version 4 changes.
2. Back up the database. The v1 to v2 step in particular adds columns to the player hour and day tables.
3. Restart normally with the new JAR and confirm the schema migration and durable batch recovery logs are free of errors.
4. Check `/stats status` and `/stats db ping`.
5. Generate some test activity, run `/stats flush`, and confirm the fact and ingest tables grew.

Moving from SQLite to PostgreSQL or MySQL requires external tooling, because the plugin has no export or import command. [DATA_MIGRATION.md](DATA_MIGRATION.md) is a design proposal for that work, not a description of shipped behavior.
