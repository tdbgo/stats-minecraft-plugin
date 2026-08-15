package com.playcity.stats.collect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

public final class Keys {
    private Keys() {}

    public static long epochHour(Instant instant) {
        return instant.getEpochSecond() / 3600L;
    }

    public static Instant hourStartInstant(long epochHour) {
        return Instant.ofEpochSecond(epochHour * 3600L);
    }

    public static LocalDate utcDay(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    public record PlayerHourKey(UUID playerUuid, long epochHour) {
        public PlayerHourKey {
            Objects.requireNonNull(playerUuid, "playerUuid");
        }
    }

    public record PlayerDayKey(UUID playerUuid, LocalDate day) {
        public PlayerDayKey {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(day, "day");
        }
    }

    public record CommandHourKey(UUID playerUuid, long epochHour, String commandKey, String variantKey) {
        public CommandHourKey {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(commandKey, "commandKey");
            Objects.requireNonNull(variantKey, "variantKey");
        }
    }

    public record BlockGroupDayKey(UUID playerUuid, LocalDate day, String groupKey, int action) {
        public BlockGroupDayKey {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(day, "day");
            Objects.requireNonNull(groupKey, "groupKey");
        }
    }

    public record DeathDayKey(UUID playerUuid, LocalDate day, String cause) {
        public DeathDayKey {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(day, "day");
            Objects.requireNonNull(cause, "cause");
        }
    }
}

