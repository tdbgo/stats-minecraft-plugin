package com.playcity.stats.db;

import com.playcity.stats.collect.Keys;
import com.playcity.stats.collect.StatsAccumulator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static com.playcity.stats.collect.Keys.*;

public final class StatsWriter {
    public enum FlushResult {
        EMPTY,
        APPLIED,
        ALREADY_APPLIED
    }

    private final DataSource dataSource;
    private final SqlDialect dialect;
    private final String p;
    private final int maxBatchRows;
    private final int queryTimeoutSeconds;
    private final Calendar utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    private final Map<String, Long> commandIdCache = new HashMap<>();
    private final Map<String, Long> variantIdCache = new HashMap<>();

    public StatsWriter(DatabaseManager db, int maxBatchRows, int queryTimeoutSeconds) {
        this(db.dataSource(), db.dialect(), db.tablePrefix(), maxBatchRows, queryTimeoutSeconds);
    }

    StatsWriter(DataSource dataSource, SqlDialect dialect, String tablePrefix, int maxBatchRows, int queryTimeoutSeconds) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.p = tablePrefix;
        this.maxBatchRows = Math.max(1, maxBatchRows);
        this.queryTimeoutSeconds = Math.max(1, queryTimeoutSeconds);
    }

    public FlushResult flush(StatsAccumulator.FlushSnapshot snapshot) throws SQLException {
        if (snapshot.isEmpty()) {
            return FlushResult.EMPTY;
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!claimBatch(connection, snapshot)) {
                    connection.commit();
                    return FlushResult.ALREADY_APPLIED;
                }

                upsertPlayers(connection, snapshot);
                insertSessions(connection, snapshot);
                upsertPlayerDay(connection, snapshot);
                upsertPlayerHour(connection, snapshot);
                upsertBlockGroupDay(connection, snapshot);
                upsertDeathDay(connection, snapshot);
                upsertCommands(connection, snapshot);

                connection.commit();
                return FlushResult.APPLIED;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                commandIdCache.clear();
                variantIdCache.clear();
                throw e;
            }
        }
    }

    private boolean claimBatch(Connection connection, StatsAccumulator.FlushSnapshot snapshot) throws SQLException {
        String table = p + "ingest_batch";
        String sql = switch (dialect) {
            case POSTGRES -> "INSERT INTO " + table + "(batch_id, created_at) VALUES (?, ?) ON CONFLICT (batch_id) DO NOTHING";
            case MYSQL -> "INSERT IGNORE INTO " + table + "(batch_id, created_at) VALUES (?, ?)";
            case SQLITE -> "INSERT OR IGNORE INTO " + table + "(batch_id, created_at) VALUES (?, ?)";
        };

        try (PreparedStatement st = prepare(connection, sql)) {
            setUuid(st, 1, snapshot.batchId());
            setInstant(st, 2, snapshot.createdAt());
            return st.executeUpdate() == 1;
        }
    }

    private void upsertPlayers(Connection c, StatsAccumulator.FlushSnapshot snapshot) throws SQLException {
        if (snapshot.players().isEmpty()) {
            return;
        }
        String sql = switch (dialect) {
            case POSTGRES -> "INSERT INTO " + p + "dim_player(player_uuid, first_seen_at, last_seen_at, last_known_name) " +
                "VALUES (?,?,?,?) " +
                "ON CONFLICT (player_uuid) DO UPDATE SET " +
                "first_seen_at=LEAST(EXCLUDED.first_seen_at," + p + "dim_player.first_seen_at), " +
                "last_seen_at=GREATEST(EXCLUDED.last_seen_at," + p + "dim_player.last_seen_at), " +
                "last_known_name=COALESCE(EXCLUDED.last_known_name," + p + "dim_player.last_known_name)";
            case MYSQL -> "INSERT INTO " + p + "dim_player(player_uuid, first_seen_at, last_seen_at, last_known_name) " +
                "VALUES (?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE " +
                "first_seen_at=LEAST(VALUES(first_seen_at), first_seen_at), " +
                "last_seen_at=GREATEST(VALUES(last_seen_at), last_seen_at), " +
                "last_known_name=COALESCE(VALUES(last_known_name), last_known_name)";
            case SQLITE -> "INSERT INTO " + p + "dim_player(player_uuid, first_seen_at, last_seen_at, last_known_name) " +
                "VALUES (?,?,?,?) " +
                "ON CONFLICT(player_uuid) DO UPDATE SET " +
                "first_seen_at=MIN(excluded.first_seen_at, first_seen_at), " +
                "last_seen_at=MAX(excluded.last_seen_at, last_seen_at), " +
                "last_known_name=COALESCE(excluded.last_known_name, last_known_name)";
        };

        try (PreparedStatement st = prepare(c, sql)) {
            int batchCount = 0;
            for (StatsAccumulator.PlayerIdentity pi : snapshot.players().values()) {
                setUuid(st, 1, pi.playerUuid());
                setInstant(st, 2, pi.firstSeenAt());
                setInstant(st, 3, pi.lastSeenAt());
                st.setString(4, pi.lastKnownName());
                st.addBatch();
                batchCount = executeBatchIfFull(st, batchCount + 1);
            }
            executeRemainingBatch(st, batchCount);
        }
    }

    private void insertSessions(Connection c, StatsAccumulator.FlushSnapshot snapshot) throws SQLException {
        if (snapshot.sessions().isEmpty()) {
            return;
        }
        String table = p + "fact_session";
        String sql = switch (dialect) {
            case POSTGRES, MYSQL -> "INSERT INTO " + table + "(player_uuid, join_at, quit_at, duration_sec, afk_sec, join_world, quit_world) VALUES (?,?,?,?,?,?,?)";
            case SQLITE -> "INSERT INTO " + table + "(player_uuid, join_at, quit_at, duration_sec, afk_sec, join_world, quit_world) VALUES (?,?,?,?,?,?,?)";
        };

        try (PreparedStatement st = prepare(c, sql)) {
            int batchCount = 0;
            for (StatsAccumulator.SessionRow row : snapshot.sessions()) {
                setUuid(st, 1, row.playerUuid());
                setInstant(st, 2, row.joinAt());
                setInstant(st, 3, row.quitAt());
                st.setInt(4, row.durationSec());
                st.setInt(5, row.afkSec());
                st.setString(6, emptyToNull(row.joinWorld()));
                st.setString(7, emptyToNull(row.quitWorld()));
                st.addBatch();
                batchCount = executeBatchIfFull(st, batchCount + 1);
            }
            executeRemainingBatch(st, batchCount);
        }
    }

    private void upsertPlayerDay(Connection c, StatsAccumulator.FlushSnapshot snapshot) throws SQLException {
        if (snapshot.dayDeltas().isEmpty()) {
            return;
        }
        String table = p + "fact_player_day";
        String sql = switch (dialect) {
            case POSTGRES -> "INSERT INTO " + table + "(player_uuid, day, playtime_sec, sessions, deaths, kills_pvp, kills_mob, teleport_count, teleport_distance_m) " +
                "VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT (player_uuid, day) DO UPDATE SET " +
                "playtime_sec=" + table + ".playtime_sec + EXCLUDED.playtime_sec, " +
                "sessions=" + table + ".sessions + EXCLUDED.sessions, " +
                "deaths=" + table + ".deaths + EXCLUDED.deaths, " +
                "kills_pvp=" + table + ".kills_pvp + EXCLUDED.kills_pvp, " +
                "kills_mob=" + table + ".kills_mob + EXCLUDED.kills_mob, " +
                "teleport_count=" + table + ".teleport_count + EXCLUDED.teleport_count, " +
                "teleport_distance_m=" + table + ".teleport_distance_m + EXCLUDED.teleport_distance_m";
            case MYSQL -> "INSERT INTO " + table + "(player_uuid, day, playtime_sec, sessions, deaths, kills_pvp, kills_mob, teleport_count, teleport_distance_m) " +
                "VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE " +
                "playtime_sec=playtime_sec + VALUES(playtime_sec), " +
                "sessions=sessions + VALUES(sessions), " +
                "deaths=deaths + VALUES(deaths), " +
                "kills_pvp=kills_pvp + VALUES(kills_pvp), " +
                "kills_mob=kills_mob + VALUES(kills_mob), " +
                "teleport_count=teleport_count + VALUES(teleport_count), " +
                "teleport_distance_m=teleport_distance_m + VALUES(teleport_distance_m)";
            case SQLITE -> "INSERT INTO " + table + "(player_uuid, day, playtime_sec, sessions, deaths, kills_pvp, kills_mob, teleport_count, teleport_distance_m) " +
                "VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(player_uuid, day) DO UPDATE SET " +
                "playtime_sec=playtime_sec + excluded.playtime_sec, " +
                "sessions=sessions + excluded.sessions, " +
                "deaths=deaths + excluded.deaths, " +
                "kills_pvp=kills_pvp + excluded.kills_pvp, " +
                "kills_mob=kills_mob + excluded.kills_mob, " +
                "teleport_count=teleport_count + excluded.teleport_count, " +
                "teleport_distance_m=teleport_distance_m + excluded.teleport_distance_m";
        };

        try (PreparedStatement st = prepare(c, sql)) {
            int batchCount = 0;
            for (var e : snapshot.dayDeltas().entrySet()) {
                PlayerDayKey k = e.getKey();
                StatsAccumulator.DayDelta d = e.getValue();
                setUuid(st, 1, k.playerUuid());
                setDay(st, 2, k.day());
                st.setLong(3, d.playtimeSec());
                st.setLong(4, d.sessions());
                st.setLong(5, d.deaths());
                st.setLong(6, d.killsPvp());
                st.setLong(7, d.killsMob());
                st.setLong(8, d.teleportCount());
                st.setLong(9, d.teleportDistanceM());
                st.addBatch();
                batchCount = executeBatchIfFull(st, batchCount + 1);
            }
            executeRemainingBatch(st, batchCount);
        }
    }

    private void upsertPlayerHour(Connection c, StatsAccumulator.FlushSnapshot snapshot) throws SQLException {
        if (snapshot.hourDeltas().isEmpty()) {
            return;
        }
        String table = p + "fact_player_hour";
        String sql = switch (dialect) {
            case POSTGRES -> "INSERT INTO " + table + "(player_uuid, hour_ts, playtime_sec, afk_sec, active_minutes, chat_messages, chat_chars, commands_total, blocks_placed_total, blocks_broken_total, distance_m, teleport_count, teleport_distance_m) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT (player_uuid, hour_ts) DO UPDATE SET " +
                "playtime_sec=" + table + ".playtime_sec + EXCLUDED.playtime_sec, " +
                "afk_sec=" + table + ".afk_sec + EXCLUDED.afk_sec, " +
                "active_minutes=GREATEST(" + table + ".active_minutes, EXCLUDED.active_minutes), " +
                "chat_messages=" + table + ".chat_messages + EXCLUDED.chat_messages, " +
                "chat_chars=" + table + ".chat_chars + EXCLUDED.chat_chars, " +
                "commands_total=" + table + ".commands_total + EXCLUDED.commands_total, " +
                "blocks_placed_total=" + table + ".blocks_placed_total + EXCLUDED.blocks_placed_total, " +
                "blocks_broken_total=" + table + ".blocks_broken_total + EXCLUDED.blocks_broken_total, " +
                "distance_m=" + table + ".distance_m + EXCLUDED.distance_m, " +
                "teleport_count=" + table + ".teleport_count + EXCLUDED.teleport_count, " +
                "teleport_distance_m=" + table + ".teleport_distance_m + EXCLUDED.teleport_distance_m";
            case MYSQL -> "INSERT INTO " + table + "(player_uuid, hour_ts, playtime_sec, afk_sec, active_minutes, chat_messages, chat_chars, commands_total, blocks_placed_total, blocks_broken_total, distance_m, teleport_count, teleport_distance_m) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE " +
                "playtime_sec=playtime_sec + VALUES(playtime_sec), " +
                "afk_sec=afk_sec + VALUES(afk_sec), " +
                "active_minutes=GREATEST(active_minutes, VALUES(active_minutes)), " +
                "chat_messages=chat_messages + VALUES(chat_messages), " +
                "chat_chars=chat_chars + VALUES(chat_chars), " +
                "commands_total=commands_total + VALUES(commands_total), " +
                "blocks_placed_total=blocks_placed_total + VALUES(blocks_placed_total), " +
                "blocks_broken_total=blocks_broken_total + VALUES(blocks_broken_total), " +
                "distance_m=distance_m + VALUES(distance_m), " +
                "teleport_count=teleport_count + VALUES(teleport_count), " +
                "teleport_distance_m=teleport_distance_m + VALUES(teleport_distance_m)";
            case SQLITE -> "INSERT INTO " + table + "(player_uuid, hour_ts, playtime_sec, afk_sec, active_minutes, chat_messages, chat_chars, commands_total, blocks_placed_total, blocks_broken_total, distance_m, teleport_count, teleport_distance_m) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(player_uuid, hour_ts) DO UPDATE SET " +
                "playtime_sec=playtime_sec + excluded.playtime_sec, " +
                "afk_sec=afk_sec + excluded.afk_sec, " +
                "active_minutes=MAX(active_minutes, excluded.active_minutes), " +
                "chat_messages=chat_messages + excluded.chat_messages, " +
                "chat_chars=chat_chars + excluded.chat_chars, " +
                "commands_total=commands_total + excluded.commands_total, " +
                "blocks_placed_total=blocks_placed_total + excluded.blocks_placed_total, " +
                "blocks_broken_total=blocks_broken_total + excluded.blocks_broken_total, " +
                "distance_m=distance_m + excluded.distance_m, " +
                "teleport_count=teleport_count + excluded.teleport_count, " +
                "teleport_distance_m=teleport_distance_m + excluded.teleport_distance_m";
        };

        try (PreparedStatement st = prepare(c, sql)) {
            int batchCount = 0;
            for (var e : snapshot.hourDeltas().entrySet()) {
                PlayerHourKey k = e.getKey();
                StatsAccumulator.HourDelta d = e.getValue();
                int activeMinutes = bitCount(snapshot.activeMinuteBits().get(k));
                setUuid(st, 1, k.playerUuid());
                setHourTs(st, 2, k.epochHour());
                st.setLong(3, d.playtimeSec());
                st.setLong(4, d.afkSec());
                st.setInt(5, activeMinutes);
                st.setLong(6, d.chatMessages());
                st.setLong(7, d.chatChars());
                st.setLong(8, d.commandsTotal());
                st.setLong(9, d.blocksPlaced());
                st.setLong(10, d.blocksBroken());
                st.setLong(11, d.distanceM());
                st.setLong(12, d.teleportCount());
                st.setLong(13, d.teleportDistanceM());
                st.addBatch();
                batchCount = executeBatchIfFull(st, batchCount + 1);
            }
            executeRemainingBatch(st, batchCount);
        }
    }

    private void upsertBlockGroupDay(Connection c, StatsAccumulator.FlushSnapshot snapshot) throws SQLException {
        if (snapshot.blockGroupDayCounts().isEmpty()) {
            return;
        }
        String table = p + "fact_block_group_day";
        String sql = switch (dialect) {
            case POSTGRES -> "INSERT INTO " + table + "(player_uuid, day, group_key, action, count) VALUES (?,?,?,?,?) " +
                "ON CONFLICT (player_uuid, day, group_key, action) DO UPDATE SET count=" + table + ".count + EXCLUDED.count";
            case MYSQL -> "INSERT INTO " + table + "(player_uuid, day, group_key, action, count) VALUES (?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE count=count + VALUES(count)";
            case SQLITE -> "INSERT INTO " + table + "(player_uuid, day, group_key, action, count) VALUES (?,?,?,?,?) " +
                "ON CONFLICT(player_uuid, day, group_key, action) DO UPDATE SET count=count + excluded.count";
        };

        try (PreparedStatement st = prepare(c, sql)) {
            int batchCount = 0;
            for (var e : snapshot.blockGroupDayCounts().entrySet()) {
                BlockGroupDayKey k = e.getKey();
                long count = e.getValue();
                setUuid(st, 1, k.playerUuid());
                setDay(st, 2, k.day());
                st.setString(3, k.groupKey());
                st.setInt(4, k.action());
                st.setLong(5, count);
                st.addBatch();
                batchCount = executeBatchIfFull(st, batchCount + 1);
            }
            executeRemainingBatch(st, batchCount);
        }
    }

    private void upsertDeathDay(Connection c, StatsAccumulator.FlushSnapshot snapshot) throws SQLException {
        if (snapshot.deathDayCounts().isEmpty()) {
            return;
        }
        String table = p + "fact_death_day";
        String sql = switch (dialect) {
            case POSTGRES -> "INSERT INTO " + table + "(player_uuid, day, cause, count) VALUES (?,?,?,?) " +
                "ON CONFLICT (player_uuid, day, cause) DO UPDATE SET count=" + table + ".count + EXCLUDED.count";
            case MYSQL -> "INSERT INTO " + table + "(player_uuid, day, cause, count) VALUES (?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE count=count + VALUES(count)";
            case SQLITE -> "INSERT INTO " + table + "(player_uuid, day, cause, count) VALUES (?,?,?,?) " +
                "ON CONFLICT(player_uuid, day, cause) DO UPDATE SET count=count + excluded.count";
        };

        try (PreparedStatement st = prepare(c, sql)) {
            int batchCount = 0;
            for (var e : snapshot.deathDayCounts().entrySet()) {
                DeathDayKey k = e.getKey();
                long count = e.getValue();
                setUuid(st, 1, k.playerUuid());
                setDay(st, 2, k.day());
                st.setString(3, k.cause());
                st.setLong(4, count);
                st.addBatch();
                batchCount = executeBatchIfFull(st, batchCount + 1);
            }
            executeRemainingBatch(st, batchCount);
        }
    }

    private void upsertCommands(Connection c, StatsAccumulator.FlushSnapshot snapshot) throws SQLException {
        if (snapshot.commandHourCounts().isEmpty()) {
            return;
        }

        // Aggregate day counts from hour counts.
        Map<DayCommandKey, Long> dayCounts = new HashMap<>();
        for (var e : snapshot.commandHourCounts().entrySet()) {
            CommandHourKey k = e.getKey();
            long count = e.getValue();
            LocalDate day = Instant.ofEpochSecond(k.epochHour() * 3600L).atZone(ZoneOffset.UTC).toLocalDate();
            DayCommandKey dk = new DayCommandKey(k.playerUuid(), day, k.commandKey(), k.variantKey());
            dayCounts.merge(dk, count, Long::sum);
        }

        String insertCommand = switch (dialect) {
            case POSTGRES -> "INSERT INTO " + p + "dim_command(command_key, family) VALUES (?, ?) " +
                "ON CONFLICT (command_key) DO UPDATE SET family=COALESCE(" + p + "dim_command.family, EXCLUDED.family)";
            case MYSQL -> "INSERT INTO " + p + "dim_command(command_key, family) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE family=COALESCE(family, VALUES(family))";
            case SQLITE -> "INSERT INTO " + p + "dim_command(command_key, family) VALUES (?, ?) " +
                "ON CONFLICT(command_key) DO UPDATE SET family=COALESCE(family, excluded.family)";
        };

        String selectCommandId = "SELECT command_id FROM " + p + "dim_command WHERE command_key = ?";

        String insertVariant = switch (dialect) {
            case POSTGRES -> "INSERT INTO " + p + "dim_command_variant(command_id, variant_key) VALUES (?, ?) " +
                "ON CONFLICT (command_id, variant_key) DO NOTHING";
            case MYSQL -> "INSERT INTO " + p + "dim_command_variant(command_id, variant_key) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE variant_id=variant_id";
            case SQLITE -> "INSERT INTO " + p + "dim_command_variant(command_id, variant_key) VALUES (?, ?) " +
                "ON CONFLICT(command_id, variant_key) DO NOTHING";
        };

        String selectVariantId = "SELECT variant_id FROM " + p + "dim_command_variant WHERE command_id = ? AND variant_key = ?";

        String factHourSql = switch (dialect) {
            case POSTGRES -> "INSERT INTO " + p + "fact_command_hour(player_uuid, hour_ts, variant_id, count) VALUES (?,?,?,?) " +
                "ON CONFLICT (player_uuid, hour_ts, variant_id) DO UPDATE SET count=" + p + "fact_command_hour.count + EXCLUDED.count";
            case MYSQL -> "INSERT INTO " + p + "fact_command_hour(player_uuid, hour_ts, variant_id, count) VALUES (?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE count=count + VALUES(count)";
            case SQLITE -> "INSERT INTO " + p + "fact_command_hour(player_uuid, hour_ts, variant_id, count) VALUES (?,?,?,?) " +
                "ON CONFLICT(player_uuid, hour_ts, variant_id) DO UPDATE SET count=count + excluded.count";
        };

        String factDaySql = switch (dialect) {
            case POSTGRES -> "INSERT INTO " + p + "fact_command_day(player_uuid, day, variant_id, count) VALUES (?,?,?,?) " +
                "ON CONFLICT (player_uuid, day, variant_id) DO UPDATE SET count=" + p + "fact_command_day.count + EXCLUDED.count";
            case MYSQL -> "INSERT INTO " + p + "fact_command_day(player_uuid, day, variant_id, count) VALUES (?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE count=count + VALUES(count)";
            case SQLITE -> "INSERT INTO " + p + "fact_command_day(player_uuid, day, variant_id, count) VALUES (?,?,?,?) " +
                "ON CONFLICT(player_uuid, day, variant_id) DO UPDATE SET count=count + excluded.count";
        };

        try (PreparedStatement insertCommandSt = prepare(c, insertCommand);
             PreparedStatement selectCommandIdSt = prepare(c, selectCommandId);
             PreparedStatement insertVariantSt = prepare(c, insertVariant);
             PreparedStatement selectVariantIdSt = prepare(c, selectVariantId);
             PreparedStatement factHourSt = prepare(c, factHourSql);
             PreparedStatement factDaySt = prepare(c, factDaySql)) {

            // Ensure all commands + variants exist
            for (var e : snapshot.commandHourCounts().entrySet()) {
                CommandHourKey k = e.getKey();
                long commandId = ensureCommand(c, insertCommandSt, selectCommandIdSt, k.commandKey());
                ensureVariant(c, insertVariantSt, selectVariantIdSt, commandId, k.variantKey());
            }

            int hourBatchCount = 0;
            for (var e : snapshot.commandHourCounts().entrySet()) {
                CommandHourKey k = e.getKey();
                long count = e.getValue();
                long commandId = commandIdCache.get(k.commandKey());
                long variantId = variantIdCache.get(variantCacheKey(commandId, k.variantKey()));

                setUuid(factHourSt, 1, k.playerUuid());
                setHourTs(factHourSt, 2, k.epochHour());
                factHourSt.setLong(3, variantId);
                factHourSt.setLong(4, count);
                factHourSt.addBatch();
                hourBatchCount = executeBatchIfFull(factHourSt, hourBatchCount + 1);
            }
            executeRemainingBatch(factHourSt, hourBatchCount);

            int dayBatchCount = 0;
            for (var e : dayCounts.entrySet()) {
                DayCommandKey k = e.getKey();
                long count = e.getValue();
                long commandId = commandIdCache.get(k.commandKey);
                long variantId = variantIdCache.get(variantCacheKey(commandId, k.variantKey));

                setUuid(factDaySt, 1, k.playerUuid);
                setDay(factDaySt, 2, k.day);
                factDaySt.setLong(3, variantId);
                factDaySt.setLong(4, count);
                factDaySt.addBatch();
                dayBatchCount = executeBatchIfFull(factDaySt, dayBatchCount + 1);
            }
            executeRemainingBatch(factDaySt, dayBatchCount);
        }
    }

    private long ensureCommand(Connection c, PreparedStatement insert, PreparedStatement select, String commandKey) throws SQLException {
        Long cached = commandIdCache.get(commandKey);
        if (cached != null) {
            return cached;
        }

        String family = commandKey.startsWith("worldedit:") ? "worldedit" : null;
        insert.setString(1, commandKey);
        insert.setString(2, family);
        insert.executeUpdate();

        select.setString(1, commandKey);
        try (var rs = select.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("Failed to resolve command_id for command_key=" + commandKey);
            }
            long id = rs.getLong(1);
            commandIdCache.put(commandKey, id);
            return id;
        }
    }

    private long ensureVariant(Connection c, PreparedStatement insert, PreparedStatement select, long commandId, String variantKey) throws SQLException {
        String vk = variantKey == null ? "" : variantKey;
        String cacheKey = variantCacheKey(commandId, vk);
        Long cached = variantIdCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        insert.setLong(1, commandId);
        insert.setString(2, vk);
        insert.executeUpdate();

        select.setLong(1, commandId);
        select.setString(2, vk);
        try (var rs = select.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("Failed to resolve variant_id for command_id=" + commandId + " variant_key=" + vk);
            }
            long id = rs.getLong(1);
            variantIdCache.put(cacheKey, id);
            return id;
        }
    }

    private static String variantCacheKey(long commandId, String variantKey) {
        return commandId + "|" + (variantKey == null ? "" : variantKey);
    }

    private int bitCount(Long bits) {
        if (bits == null) {
            return 0;
        }
        return Long.bitCount(bits);
    }

    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(queryTimeoutSeconds);
        return statement;
    }

    private int executeBatchIfFull(PreparedStatement statement, int batchCount) throws SQLException {
        if (batchCount < maxBatchRows) {
            return batchCount;
        }
        statement.executeBatch();
        statement.clearBatch();
        return 0;
    }

    private void executeRemainingBatch(PreparedStatement statement, int batchCount) throws SQLException {
        if (batchCount > 0) {
            statement.executeBatch();
        }
    }

    private void setUuid(PreparedStatement st, int index, UUID uuid) throws SQLException {
        switch (dialect) {
            case POSTGRES -> st.setObject(index, uuid);
            case MYSQL, SQLITE -> st.setBytes(index, UuidCodec.toBytes(uuid));
        }
    }

    private void setInstant(PreparedStatement st, int index, Instant instant) throws SQLException {
        switch (dialect) {
            case SQLITE -> st.setString(index, instant.atZone(ZoneOffset.UTC).toInstant().toString());
            case POSTGRES, MYSQL -> st.setTimestamp(index, Timestamp.from(instant), utcCalendar);
        }
    }

    private void setHourTs(PreparedStatement st, int index, long epochHour) throws SQLException {
        Instant hourStart = Keys.hourStartInstant(epochHour);
        setInstant(st, index, hourStart);
    }

    private void setDay(PreparedStatement st, int index, LocalDate day) throws SQLException {
        switch (dialect) {
            case SQLITE -> st.setString(index, day.toString());
            case POSTGRES, MYSQL -> st.setDate(index, Date.valueOf(day));
        }
    }

    private static String emptyToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s;
    }

    private record DayCommandKey(UUID playerUuid, LocalDate day, String commandKey, String variantKey) {}
}
