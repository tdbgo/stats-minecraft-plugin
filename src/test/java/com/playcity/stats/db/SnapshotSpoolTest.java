package com.playcity.stats.db;

import com.playcity.stats.collect.StatsAccumulator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotSpoolTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripsCompleteSnapshotAndDeletesOnlyOnCompletion() throws Exception {
        SnapshotSpool spool = new SnapshotSpool(tempDir, "sqlite|test_|database-a");
        StatsAccumulator.FlushSnapshot snapshot = fullSnapshot();

        spool.store(snapshot);
        spool.store(snapshot);

        assertEquals(1, spool.pendingFileCount());
        assertTrue(spool.contains(snapshot.batchId()));
        assertEquals(List.of(snapshot), spool.loadAll());

        spool.complete(snapshot);
        assertEquals(0, spool.pendingFileCount());
        assertFalse(spool.contains(snapshot.batchId()));
    }

    @Test
    void completeTemporaryFileIsRecoveredAfterInterruptedRename() throws Exception {
        String identity = "postgres|test_|database-b";
        SnapshotSpool first = new SnapshotSpool(tempDir, identity);
        StatsAccumulator.FlushSnapshot snapshot = fullSnapshot();
        first.store(snapshot);

        Path directory = tempDir.resolve(first.storageId());
        Path pending = directory.resolve(snapshot.batchId() + ".pending");
        Path temporary = directory.resolve(snapshot.batchId() + ".tmp-interrupted");
        Files.move(pending, temporary, StandardCopyOption.REPLACE_EXISTING);

        SnapshotSpool recovered = new SnapshotSpool(tempDir, identity);

        assertEquals(List.of(snapshot), recovered.loadAll());
        assertTrue(Files.notExists(temporary));
    }

    @Test
    void corruptSpoolFileIsPreservedAndRejected() throws Exception {
        SnapshotSpool spool = new SnapshotSpool(tempDir, "mysql|test_|database-c");
        Path corrupt = tempDir.resolve(spool.storageId()).resolve(UUID.randomUUID() + ".pending");
        Files.writeString(corrupt, "corrupt");

        assertThrows(IOException.class, spool::loadAll);
        assertTrue(Files.exists(corrupt));
    }

    private static StatsAccumulator.FlushSnapshot fullSnapshot() {
        StatsAccumulator accumulator = new StatsAccumulator();
        UUID player = UUID.fromString("89e6b730-f1b5-4b76-a29b-dca7f57603b4");
        Instant at = Instant.parse("2026-08-09T12:01:02.123456789Z");
        accumulator.upsertPlayer(player, at, at.plusSeconds(90), "Tester");
        accumulator.addPlaytime(player, at, 42);
        accumulator.addAfk(player, at, 5);
        accumulator.markActiveMinute(player, at);
        accumulator.addChat(player, at, 12);
        accumulator.addCommand(player, at, "minecraft:gamemode", "mode=creative");
        accumulator.addBlock(player, at, true, "stone");
        accumulator.addBlock(player, at, false, "container");
        accumulator.addDeath(player, at, "fall");
        accumulator.addKill(player, at, false);
        accumulator.addSessionCount(player, at);
        accumulator.addDistance(player, at, 9);
        accumulator.addTeleport(player, at, 15);
        accumulator.addSession(new StatsAccumulator.SessionRow(
            player, at, at.plusSeconds(60), 60, 5, "world", "world_nether"
        ));
        return accumulator.drainDeltas();
    }
}
