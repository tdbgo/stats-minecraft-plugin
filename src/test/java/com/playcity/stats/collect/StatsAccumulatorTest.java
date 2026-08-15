package com.playcity.stats.collect;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatsAccumulatorTest {
    @Test
    void concurrentDrainDoesNotLoseUpdates() throws Exception {
        StatsAccumulator accumulator = new StatsAccumulator();
        UUID player = UUID.randomUUID();
        Instant at = Instant.parse("2026-07-20T12:00:00Z");
        int workers = 6;
        int updatesPerWorker = 5_000;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger remainingWriters = new AtomicInteger(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers + 1);

        try {
            List<Future<?>> writers = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                writers.add(executor.submit(() -> {
                    start.await();
                    try {
                        for (int j = 0; j < updatesPerWorker; j++) {
                            accumulator.addPlaytime(player, at, 1);
                        }
                    } finally {
                        remainingWriters.decrementAndGet();
                    }
                    return null;
                }));
            }

            Future<Long> drainedTotal = executor.submit(() -> {
                start.await();
                long total = 0;
                while (remainingWriters.get() > 0) {
                    total += playtime(accumulator.drainDeltas());
                    Thread.onSpinWait();
                }
                return total;
            });

            start.countDown();
            for (Future<?> writer : writers) {
                writer.get();
            }

            long total = drainedTotal.get() + playtime(accumulator.drainDeltas());
            assertEquals((long) workers * updatesPerWorker, total);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void snapshotsAreImmutableAndActiveMinutesRemainCumulativeWithinTheHour() {
        StatsAccumulator accumulator = new StatsAccumulator();
        UUID player = UUID.randomUUID();
        Instant firstMinute = Instant.parse("2026-07-20T12:01:10Z");
        Keys.PlayerHourKey key = new Keys.PlayerHourKey(player, Keys.epochHour(firstMinute));

        accumulator.markActiveMinute(player, firstMinute);
        StatsAccumulator.FlushSnapshot first = accumulator.drainDeltas();
        assertEquals(1, Long.bitCount(first.activeMinuteBits().get(key)));
        assertThrows(UnsupportedOperationException.class, first.hourDeltas()::clear);

        accumulator.markActiveMinute(player, firstMinute.plusSeconds(60));
        StatsAccumulator.FlushSnapshot second = accumulator.drainDeltas();
        assertEquals(2, Long.bitCount(second.activeMinuteBits().get(key)));

        accumulator.pruneActiveMinutesBefore(key.epochHour() + 1);
        accumulator.addPlaytime(player, firstMinute, 1);
        StatsAccumulator.FlushSnapshot afterPrune = accumulator.drainDeltas();
        assertFalse(afterPrune.activeMinuteBits().containsKey(key));
    }

    private static long playtime(StatsAccumulator.FlushSnapshot snapshot) {
        return snapshot.hourDeltas().values().stream().mapToLong(StatsAccumulator.HourDelta::playtimeSec).sum();
    }
}
