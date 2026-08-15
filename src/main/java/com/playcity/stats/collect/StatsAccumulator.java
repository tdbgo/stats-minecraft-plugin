package com.playcity.stats.collect;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.playcity.stats.collect.Keys.*;

public final class StatsAccumulator {
    public record PlayerIdentity(UUID playerUuid, Instant firstSeenAt, Instant lastSeenAt, String lastKnownName) {}

    public record SessionRow(UUID playerUuid, Instant joinAt, Instant quitAt, int durationSec, int afkSec, String joinWorld, String quitWorld) {}

    public record HourDelta(
        long playtimeSec,
        long afkSec,
        long chatMessages,
        long chatChars,
        long commandsTotal,
        long blocksPlaced,
        long blocksBroken,
        long distanceM,
        long teleportCount,
        long teleportDistanceM
    ) {}

    public record DayDelta(
        long playtimeSec,
        long sessions,
        long deaths,
        long killsPvp,
        long killsMob,
        long teleportCount,
        long teleportDistanceM
    ) {}

    private Buffer active = new Buffer();
    private final Map<PlayerHourKey, Long> cumulativeActiveMinuteBits = new HashMap<>();

    public synchronized void upsertPlayer(UUID uuid, Instant firstSeenAt, Instant lastSeenAt, String name) {
        active.players.compute(uuid, (key, existing) -> {
            if (existing == null) {
                return new PlayerIdentity(uuid, firstSeenAt, lastSeenAt, name);
            }
            Instant first = existing.firstSeenAt().isAfter(firstSeenAt) ? firstSeenAt : existing.firstSeenAt();
            Instant last = existing.lastSeenAt().isBefore(lastSeenAt) ? lastSeenAt : existing.lastSeenAt();
            String resolvedName = name != null && !name.isBlank() ? name : existing.lastKnownName();
            return new PlayerIdentity(uuid, first, last, resolvedName);
        });
    }

    public synchronized void addSession(SessionRow row) {
        active.sessions.add(row);
    }

    public synchronized void addPlaytime(UUID uuid, Instant at, long seconds) {
        if (seconds <= 0) {
            return;
        }
        MutableHourDelta hour = active.hour(uuid, at);
        hour.playtimeSec += seconds;
        active.day(uuid, at).playtimeSec += seconds;
    }

    public synchronized void addAfk(UUID uuid, Instant at, long seconds) {
        if (seconds <= 0) {
            return;
        }
        active.hour(uuid, at).afkSec += seconds;
    }

    public synchronized void markActiveMinute(UUID uuid, Instant at) {
        PlayerHourKey key = new PlayerHourKey(uuid, epochHour(at));
        int minute = (int) ((at.getEpochSecond() % 3600L) / 60L);
        long mask = 1L << minute;
        cumulativeActiveMinuteBits.merge(key, mask, (left, right) -> left | right);
        active.activeMinuteBits.put(key, cumulativeActiveMinuteBits.get(key));
        active.hourDeltas.computeIfAbsent(key, ignored -> new MutableHourDelta());
    }

    public synchronized void addChat(UUID uuid, Instant at, int chars) {
        MutableHourDelta delta = active.hour(uuid, at);
        delta.chatMessages++;
        if (chars > 0) {
            delta.chatChars += chars;
        }
    }

    public synchronized void addCommand(UUID uuid, Instant at, String commandKey, String variantKey) {
        active.hour(uuid, at).commandsTotal++;
        CommandHourKey key = new CommandHourKey(uuid, epochHour(at), commandKey, variantKey == null ? "" : variantKey);
        active.commandHourCounts.merge(key, 1L, Long::sum);
    }

    public synchronized void addBlock(UUID uuid, Instant at, boolean place, String groupKey) {
        MutableHourDelta delta = active.hour(uuid, at);
        if (place) {
            delta.blocksPlaced++;
        } else {
            delta.blocksBroken++;
        }

        BlockGroupDayKey key = new BlockGroupDayKey(uuid, utcDay(at), groupKey, place ? 0 : 1);
        active.blockGroupDayCounts.merge(key, 1L, Long::sum);
    }

    public synchronized void addDeath(UUID uuid, Instant at, String cause) {
        active.day(uuid, at).deaths++;
        if (cause != null && !cause.isBlank()) {
            active.deathDayCounts.merge(new DeathDayKey(uuid, utcDay(at), cause), 1L, Long::sum);
        }
    }

    public synchronized void addKill(UUID uuid, Instant at, boolean pvp) {
        MutableDayDelta delta = active.day(uuid, at);
        if (pvp) {
            delta.killsPvp++;
        } else {
            delta.killsMob++;
        }
    }

    public synchronized void addSessionCount(UUID uuid, Instant at) {
        active.day(uuid, at).sessions++;
    }

    public synchronized void addDistance(UUID uuid, Instant at, long meters) {
        if (meters > 0) {
            active.hour(uuid, at).distanceM += meters;
        }
    }

    public synchronized void addTeleport(UUID uuid, Instant at, long meters) {
        MutableHourDelta hour = active.hour(uuid, at);
        hour.teleportCount++;
        if (meters > 0) {
            hour.teleportDistanceM += meters;
        }

        MutableDayDelta day = active.day(uuid, at);
        day.teleportCount++;
        if (meters > 0) {
            day.teleportDistanceM += meters;
        }
    }

    public FlushSnapshot drainDeltas() {
        Buffer drained;
        synchronized (this) {
            drained = active;
            active = new Buffer();
        }
        if (drained.isEmpty()) {
            return FlushSnapshot.empty();
        }
        return drained.snapshot();
    }

    public synchronized void pruneActiveMinutesBefore(long epochHourExclusive) {
        cumulativeActiveMinuteBits.keySet().removeIf(key -> key.epochHour() < epochHourExclusive);
    }

    public synchronized boolean hasPendingDeltas() {
        return !active.isEmpty();
    }

    public record FlushSnapshot(
        UUID batchId,
        Instant createdAt,
        Map<UUID, PlayerIdentity> players,
        Map<PlayerHourKey, HourDelta> hourDeltas,
        Map<PlayerDayKey, DayDelta> dayDeltas,
        Map<PlayerHourKey, Long> activeMinuteBits,
        Map<CommandHourKey, Long> commandHourCounts,
        Map<BlockGroupDayKey, Long> blockGroupDayCounts,
        Map<DeathDayKey, Long> deathDayCounts,
        List<SessionRow> sessions
    ) {
        private static final FlushSnapshot EMPTY = new FlushSnapshot(
            new UUID(0, 0),
            Instant.EPOCH,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of()
        );

        private static FlushSnapshot empty() {
            return EMPTY;
        }

        public boolean isEmpty() {
            return players.isEmpty()
                && hourDeltas.isEmpty()
                && dayDeltas.isEmpty()
                && commandHourCounts.isEmpty()
                && blockGroupDayCounts.isEmpty()
                && deathDayCounts.isEmpty()
                && sessions.isEmpty();
        }

        public int rowCount() {
            return players.size()
                + hourDeltas.size()
                + dayDeltas.size()
                + commandHourCounts.size()
                + blockGroupDayCounts.size()
                + deathDayCounts.size()
                + sessions.size();
        }
    }

    private static final class Buffer {
        private final Map<UUID, PlayerIdentity> players = new HashMap<>();
        private final Map<PlayerHourKey, MutableHourDelta> hourDeltas = new HashMap<>();
        private final Map<PlayerDayKey, MutableDayDelta> dayDeltas = new HashMap<>();
        private final Map<PlayerHourKey, Long> activeMinuteBits = new HashMap<>();
        private final Map<CommandHourKey, Long> commandHourCounts = new HashMap<>();
        private final Map<BlockGroupDayKey, Long> blockGroupDayCounts = new HashMap<>();
        private final Map<DeathDayKey, Long> deathDayCounts = new HashMap<>();
        private final List<SessionRow> sessions = new ArrayList<>();

        private MutableHourDelta hour(UUID uuid, Instant at) {
            return hourDeltas.computeIfAbsent(new PlayerHourKey(uuid, epochHour(at)), ignored -> new MutableHourDelta());
        }

        private MutableDayDelta day(UUID uuid, Instant at) {
            return dayDeltas.computeIfAbsent(new PlayerDayKey(uuid, utcDay(at)), ignored -> new MutableDayDelta());
        }

        private boolean isEmpty() {
            return players.isEmpty()
                && hourDeltas.isEmpty()
                && dayDeltas.isEmpty()
                && commandHourCounts.isEmpty()
                && blockGroupDayCounts.isEmpty()
                && deathDayCounts.isEmpty()
                && sessions.isEmpty();
        }

        private FlushSnapshot snapshot() {
            Map<PlayerHourKey, HourDelta> hours = new HashMap<>();
            hourDeltas.forEach((key, value) -> hours.put(key, value.freeze()));

            Map<PlayerDayKey, DayDelta> days = new HashMap<>();
            dayDeltas.forEach((key, value) -> days.put(key, value.freeze()));

            return new FlushSnapshot(
                UUID.randomUUID(),
                Instant.now(),
                Map.copyOf(players),
                Map.copyOf(hours),
                Map.copyOf(days),
                Map.copyOf(activeMinuteBits),
                Map.copyOf(commandHourCounts),
                Map.copyOf(blockGroupDayCounts),
                Map.copyOf(deathDayCounts),
                List.copyOf(sessions)
            );
        }
    }

    private static final class MutableHourDelta {
        private long playtimeSec;
        private long afkSec;
        private long chatMessages;
        private long chatChars;
        private long commandsTotal;
        private long blocksPlaced;
        private long blocksBroken;
        private long distanceM;
        private long teleportCount;
        private long teleportDistanceM;

        private HourDelta freeze() {
            return new HourDelta(
                playtimeSec,
                afkSec,
                chatMessages,
                chatChars,
                commandsTotal,
                blocksPlaced,
                blocksBroken,
                distanceM,
                teleportCount,
                teleportDistanceM
            );
        }
    }

    private static final class MutableDayDelta {
        private long playtimeSec;
        private long sessions;
        private long deaths;
        private long killsPvp;
        private long killsMob;
        private long teleportCount;
        private long teleportDistanceM;

        private DayDelta freeze() {
            return new DayDelta(playtimeSec, sessions, deaths, killsPvp, killsMob, teleportCount, teleportDistanceM);
        }
    }
}
