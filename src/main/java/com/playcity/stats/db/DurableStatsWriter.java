package com.playcity.stats.db;

import com.playcity.stats.collect.StatsAccumulator.FlushSnapshot;

public final class DurableStatsWriter {
    private final StatsWriter writer;
    private final SnapshotSpool spool;

    public DurableStatsWriter(StatsWriter writer, SnapshotSpool spool) {
        this.writer = writer;
        this.spool = spool;
    }

    public StatsWriter.FlushResult flush(FlushSnapshot snapshot) throws Exception {
        if (snapshot.isEmpty()) {
            return StatsWriter.FlushResult.EMPTY;
        }
        spool.store(snapshot);
        StatsWriter.FlushResult result = writer.flush(snapshot);
        spool.complete(snapshot);
        return result;
    }

    public RecoveryResult recover() throws Exception {
        int snapshots = 0;
        int rows = 0;
        for (FlushSnapshot snapshot : spool.loadAll()) {
            writer.flush(snapshot);
            spool.complete(snapshot);
            snapshots++;
            rows += snapshot.rowCount();
        }
        return new RecoveryResult(snapshots, rows);
    }

    public int pendingFileCount() {
        return spool.pendingFileCount();
    }

    public boolean isPersisted(FlushSnapshot snapshot) {
        return snapshot != null && spool.contains(snapshot.batchId());
    }

    public String storageId() {
        return spool.storageId();
    }

    public record RecoveryResult(int snapshots, int rows) {}
}
