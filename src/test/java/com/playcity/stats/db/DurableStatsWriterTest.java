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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurableStatsWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void databaseFailureRetainsSnapshotAndNextWriterRecoversIt() throws Exception {
        Path database = tempDir.resolve("durable.db");
        String identity = "sqlite|test_|" + database.toAbsolutePath();
        StatsAccumulator.FlushSnapshot snapshot = snapshot();

        HikariDataSource failedDataSource = dataSource(database);
        ensureSchema(failedDataSource);
        DurableStatsWriter failedWriter = new DurableStatsWriter(
            new StatsWriter(failedDataSource, SqlDialect.SQLITE, "test_", 100, 30),
            new SnapshotSpool(tempDir.resolve("spool"), identity)
        );
        failedDataSource.close();

        assertThrows(SQLException.class, () -> failedWriter.flush(snapshot));
        assertEquals(1, failedWriter.pendingFileCount());

        try (HikariDataSource recoveredDataSource = dataSource(database)) {
            ensureSchema(recoveredDataSource);
            DurableStatsWriter recoveredWriter = new DurableStatsWriter(
                new StatsWriter(recoveredDataSource, SqlDialect.SQLITE, "test_", 100, 30),
                new SnapshotSpool(tempDir.resolve("spool"), identity)
            );

            DurableStatsWriter.RecoveryResult recovery = recoveredWriter.recover();

            assertEquals(1, recovery.snapshots());
            assertEquals(snapshot.rowCount(), recovery.rows());
            assertEquals(0, recoveredWriter.pendingFileCount());
            try (Connection connection = recoveredDataSource.getConnection()) {
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM test_ingest_batch"));
                assertEquals(7, scalar(connection, "SELECT playtime_sec FROM test_fact_player_hour"));
            }
        }
    }

    private static StatsAccumulator.FlushSnapshot snapshot() {
        StatsAccumulator accumulator = new StatsAccumulator();
        UUID player = UUID.fromString("0f0e6720-1a5c-4cc8-b874-a2538461bba0");
        Instant at = Instant.parse("2026-08-09T10:00:00Z");
        accumulator.upsertPlayer(player, at, at, "Tester");
        accumulator.addPlaytime(player, at, 7);
        return accumulator.drainDeltas();
    }

    private static HikariDataSource dataSource(Path database) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + database.toAbsolutePath());
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
}
