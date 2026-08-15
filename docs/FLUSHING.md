# Aggregation and batch flushing

The goal is to avoid writing to the database on every event: counters accumulate in memory, and database I/O is serialized on a dedicated thread. Collection and upload run only when `setup.enabled` is `true`.

## Implementation

1. Paper events and the 5-second default tick accumulate counters in `StatsAccumulator`.
2. Every 300 seconds by default, a flush task is scheduled on the single `Stats-IO` executor.
3. `drainDeltas()` swaps the active buffer reference for a fresh one inside a short lock, and nothing else.
4. The immutable snapshot, the spool file write, and the JDBC work all happen on the I/O thread, outside that lock.
5. Before transmission the snapshot is written with a checksum to `plugins/Stats/spool/<storage-id>/<batch-id>.pending`, force-synced, then atomically moved into place.
6. One snapshot is applied as one database transaction. `flush.maxBatchRows` only divides `executeBatch()` calls; it does not change the transaction boundary.
7. The spool file is deleted only after a successful commit, or after confirming the `batch_id` was already recorded. On startup and reload, pending files matching the current database target are replayed first.

Collection methods are safe under concurrent access. Even when an asynchronous chat event and a main-thread event overlap a flush, each counted event lands in exactly one snapshot — the one before or the one after the buffer swap.

## Behavior when nothing is pending

- The 300-second timer and the local executor task still wake up.
- If the buffer is empty, `DataSource#getConnection()` is never called, so there is no SQLite file access and no remote database network traffic for that cycle.
- Remote pool defaults are `minimumIdle: 0` and `maximumPoolSize: 2`. SQLite is limited in code to a single connection.
- While any player is online, playtime and AFK seconds accrue without explicit input, so those flushes are not empty. A fully empty flush requires no players online and no events.

## Failure handling and duplicate prevention

- Each non-empty snapshot receives a UUID `batch_id`.
- The first step of the transaction claims that `batch_id` in `ingest_batch`.
- On a database error, the same immutable snapshot is retained in memory and in the local spool, and retried with the same `batch_id` on the next flush or the next startup.
- If the outcome of a commit is genuinely unknown — for example a lost response — an already-recorded `batch_id` causes the fact tables to be left alone rather than incremented again.
- A failed transaction is explicitly rolled back, and the command and variant ID caches built during it are cleared.
- Reload keeps the previous runtime's undelivered batches in a separate queue and retries them after the new runtime's flush.

`ingest_batch` grows by one row per non-empty flush. The scheduled 300-second timer alone can add up to roughly 288 rows per day and 105,120 per year when every interval has data; manual, reload, and shutdown flushes can add more. There is no automatic cleanup.

## Local spool and remaining durability limits

- Spool directories are separated by a `storage-id` hash of the database type, the safe JDBC target, and the table prefix. The password is not part of that identity.
- A corrupt file or a checksum mismatch is not deleted. Activation fails so the operator can investigate with the original preserved.
- If the database commits but the process dies before the spool file is removed, `ingest_batch.batch_id` still prevents double counting on replay.
- The spool holds only the curated aggregate snapshot — never coordinates, chat bodies, or raw command lines.
- Spool payloads are validated against a 64 MiB limit. An abnormal snapshot exceeding it fails before transmission and stays in memory.

An active memory buffer that has not yet been drained can be lost if the process is killed between flushes. A continuous per-event write-ahead log would close that window but would add file I/O to the main event path and materially increase storage, so it is not part of this release. On normal plugin disable, the final snapshot is retained for the next start when the spool write succeeds; if both the database flush and spool write fail, the plugin logs that the batch may be lost.
