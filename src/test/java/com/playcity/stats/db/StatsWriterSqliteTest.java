package com.playcity.stats.db;

import com.playcity.stats.collect.StatsAccumulator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsWriterSqliteTest {
    @TempDir
    Path tempDir;

    @Test
    void emptySnapshotDoesNotAcquireADatabaseConnection() throws Exception {
        try (HikariDataSource unconfiguredDataSource = new HikariDataSource()) {
            StatsWriter writer = new StatsWriter(unconfiguredDataSource, SqlDialect.SQLITE, "test_", 100, 30);
            StatsAccumulator.FlushSnapshot empty = new StatsAccumulator().drainDeltas();

            assertEquals(StatsWriter.FlushResult.EMPTY, writer.flush(empty));
        }
    }

    @Test
    void retryingTheSameSnapshotIsIdempotent() throws Exception {
        try (HikariDataSource dataSource = dataSource("idempotent.db")) {
            ensureSchema(dataSource);
            StatsWriter writer = new StatsWriter(dataSource, SqlDialect.SQLITE, "test_", 2, 30);
            StatsAccumulator.FlushSnapshot snapshot = fullSnapshot();

            assertEquals(StatsWriter.FlushResult.APPLIED, writer.flush(snapshot));
            assertEquals(StatsWriter.FlushResult.ALREADY_APPLIED, writer.flush(snapshot));

            try (Connection connection = dataSource.getConnection()) {
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM test_ingest_batch"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM test_fact_session"));
                assertEquals(42, scalar(connection, "SELECT playtime_sec FROM test_fact_player_hour"));
                assertEquals(1, scalar(connection, "SELECT active_minutes FROM test_fact_player_hour"));
                assertEquals(1, scalar(connection, "SELECT teleport_count FROM test_fact_player_hour"));
                assertEquals(15, scalar(connection, "SELECT teleport_distance_m FROM test_fact_player_day"));
                assertEquals(1, scalar(connection, "SELECT count FROM test_fact_command_hour"));
                assertEquals(1, scalar(connection, "SELECT count FROM test_fact_block_group_day"));
                assertEquals(1, scalar(connection, "SELECT count FROM test_fact_death_day"));
            }
        }
    }

    @Test
    void failedTransactionCanRetryAfterSchemaRepair() throws Exception {
        try (HikariDataSource dataSource = dataSource("rollback.db")) {
            ensureSchema(dataSource);
            StatsWriter writer = new StatsWriter(dataSource, SqlDialect.SQLITE, "test_", 100, 30);
            StatsAccumulator accumulator = new StatsAccumulator();
            UUID player = UUID.randomUUID();
            Instant at = Instant.parse("2026-07-20T12:00:00Z");
            accumulator.upsertPlayer(player, at, at, "Tester");
            accumulator.addCommand(player, at, "minecraft:gamemode", "mode=creative");
            StatsAccumulator.FlushSnapshot snapshot = accumulator.drainDeltas();

            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE test_fact_command_hour");
            }

            assertThrows(SQLException.class, () -> writer.flush(snapshot));
            try (Connection connection = dataSource.getConnection()) {
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM test_ingest_batch"));
            }

            ensureSchema(dataSource);
            assertEquals(StatsWriter.FlushResult.APPLIED, writer.flush(snapshot));
            try (Connection connection = dataSource.getConnection()) {
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM test_dim_command"));
                assertEquals(1, scalar(connection, "SELECT count FROM test_fact_command_hour"));
            }
        }
    }

    @Test
    void schemaV2MigratesTeleportColumns() throws Exception {
        try (HikariDataSource dataSource = dataSource("migration.db");
             Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE test_fact_player_hour (player_uuid BLOB NOT NULL, hour_ts TEXT NOT NULL, " +
                "playtime_sec INTEGER NOT NULL DEFAULT 0, afk_sec INTEGER NOT NULL DEFAULT 0, active_minutes INTEGER NOT NULL DEFAULT 0, " +
                "chat_messages INTEGER NOT NULL DEFAULT 0, chat_chars INTEGER NOT NULL DEFAULT 0, commands_total INTEGER NOT NULL DEFAULT 0, " +
                "blocks_placed_total INTEGER NOT NULL DEFAULT 0, blocks_broken_total INTEGER NOT NULL DEFAULT 0, distance_m INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(player_uuid, hour_ts))");
            statement.execute("CREATE TABLE test_fact_player_day (player_uuid BLOB NOT NULL, day TEXT NOT NULL, " +
                "playtime_sec INTEGER NOT NULL DEFAULT 0, sessions INTEGER NOT NULL DEFAULT 0, deaths INTEGER NOT NULL DEFAULT 0, " +
                "kills_pvp INTEGER NOT NULL DEFAULT 0, kills_mob INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(player_uuid, day))");

            SchemaManager.ensureSchema(connection, SqlDialect.SQLITE, "test_", true);

            assertTrue(columns(connection, "test_fact_player_hour").containsAll(Set.of("teleport_count", "teleport_distance_m")));
            assertTrue(columns(connection, "test_fact_player_day").containsAll(Set.of("teleport_count", "teleport_distance_m")));
        }
    }

    private StatsAccumulator.FlushSnapshot fullSnapshot() {
        StatsAccumulator accumulator = new StatsAccumulator();
        UUID player = UUID.randomUUID();
        Instant at = Instant.parse("2026-07-20T12:01:00Z");
        accumulator.upsertPlayer(player, at, at.plusSeconds(60), "Tester");
        accumulator.addPlaytime(player, at, 42);
        accumulator.addAfk(player, at, 5);
        accumulator.markActiveMinute(player, at);
        accumulator.addChat(player, at, 12);
        accumulator.addCommand(player, at, "minecraft:gamemode", "mode=creative");
        accumulator.addBlock(player, at, true, "stone");
        accumulator.addDeath(player, at, "fall");
        accumulator.addKill(player, at, false);
        accumulator.addSessionCount(player, at);
        accumulator.addDistance(player, at, 9);
        accumulator.addTeleport(player, at, 15);
        accumulator.addSession(new StatsAccumulator.SessionRow(player, at, at.plusSeconds(60), 60, 5, "world", "world"));
        return accumulator.drainDeltas();
    }

    private HikariDataSource dataSource(String fileName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve(fileName).toAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        return new HikariDataSource(config);
    }

    private static void ensureSchema(HikariDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            SchemaManager.ensureSchema(connection, SqlDialect.SQLITE, "test_", true);
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        return columns;
    }
}
