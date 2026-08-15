package com.playcity.stats.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.File;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerTest {
    @TempDir
    File tempDir;

    @Test
    void postgresUsesDirectDataSourceAndPreservesConnectionIdentity() {
        YamlConfiguration config = postgresConfig();

        DatabaseManager.PoolConfiguration pool = DatabaseManager.buildPoolConfiguration(
            tempDir,
            config,
            DbType.POSTGRES,
            30
        );

        HikariConfig hikari = pool.hikari();
        PGSimpleDataSource postgres = assertInstanceOf(PGSimpleDataSource.class, hikari.getDataSource());
        assertNull(hikari.getDriverClassName());
        assertNull(hikari.getJdbcUrl());
        assertEquals(expectedUrl(), pool.connectionUrl());
        assertArrayEquals(new String[]{"db.internal"}, postgres.getServerNames());
        assertArrayEquals(new int[]{5544}, postgres.getPortNumbers());
        assertEquals("block_stats", postgres.getDatabaseName());
        assertEquals("analytics", postgres.getCurrentSchema());
        assertEquals("Stats", postgres.getApplicationName());
        assertEquals(5, postgres.getConnectTimeout());
        assertEquals(30, postgres.getSocketTimeout());
        assertEquals("verify-full", postgres.getSslMode());
    }

    @Test
    void postgresPoolStartsAndDatabaseManagerClosesPhysicalConnection() {
        YamlConfiguration config = postgresConfig();
        StubPostgresDataSource stub = new StubPostgresDataSource();

        DatabaseManager manager = new DatabaseManager(tempDir, config, hikari -> {
            assertInstanceOf(PGSimpleDataSource.class, hikari.getDataSource());
            assertNull(hikari.getDriverClassName());
            hikari.setDataSource(stub);
            hikari.setMaximumPoolSize(1);
            hikari.setMinimumIdle(1);
            hikari.setInitializationFailTimeout(1_000);
            return new HikariDataSource(hikari);
        });

        assertFalse(manager.dataSource().isClosed());
        assertEquals("POSTGRES|mstats_|" + expectedUrl(), manager.storageIdentity());
        assertEquals("stats-user", stub.username);
        assertEquals("test-password", stub.password);

        manager.close();

        assertTrue(manager.dataSource().isClosed());
        assertTrue(stub.physicalConnectionClosed.get());
    }

    @Test
    void postgresConnectionFailurePropagatesWithoutDriverManagerFallback() {
        YamlConfiguration config = postgresConfig();
        FailingPostgresDataSource failing = new FailingPostgresDataSource();

        assertThrows(HikariPool.PoolInitializationException.class, () ->
            new DatabaseManager(tempDir, config, hikari -> {
                assertNull(hikari.getDriverClassName());
                hikari.setDataSource(failing);
                hikari.setConnectionTimeout(250);
                hikari.setInitializationFailTimeout(1);
                return new HikariDataSource(hikari);
            })
        );
        assertTrue(failing.attempts.get() > 0);
    }

    private static YamlConfiguration postgresConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "postgres");
        config.set("database.tablePrefix", "mstats_");
        config.set("database.host", "db.internal");
        config.set("database.port", 5544);
        config.set("database.database", "block_stats");
        config.set("database.schema", "analytics");
        config.set("database.username", "stats-user");
        config.set("database.password", "test-password");
        config.set("database.ssl.enabled", true);
        config.set("database.ssl.mode", "verify-full");
        config.set("database.pool.maximumPoolSize", 2);
        config.set("database.pool.minimumIdle", 0);
        config.set("database.pool.connectionTimeoutMs", 5_000);
        return config;
    }

    private static String expectedUrl() {
        return "jdbc:postgresql://db.internal:5544/block_stats" +
            "?applicationName=Stats" +
            "&currentSchema=analytics" +
            "&connectTimeout=5" +
            "&socketTimeout=30" +
            "&sslmode=verify-full";
    }

    private static class StubPostgresDataSource extends PGSimpleDataSource {
        private final AtomicBoolean physicalConnectionClosed = new AtomicBoolean();
        private String username;
        private String password;

        @Override
        public Connection getConnection(String username, String password) {
            this.username = username;
            this.password = password;
            return connection(physicalConnectionClosed);
        }
    }

    private static final class FailingPostgresDataSource extends PGSimpleDataSource {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            attempts.incrementAndGet();
            throw new SQLException("expected unavailable PostgreSQL fixture");
        }
    }

    private static Connection connection(AtomicBoolean closed) {
        return (Connection) Proxy.newProxyInstance(
            DatabaseManagerTest.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "close" -> {
                    closed.set(true);
                    yield null;
                }
                case "isClosed" -> closed.get();
                case "isValid" -> !closed.get();
                case "getAutoCommit" -> true;
                case "isReadOnly" -> false;
                case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                case "getNetworkTimeout" -> 0;
                case "setAutoCommit", "setReadOnly", "setTransactionIsolation", "setNetworkTimeout", "clearWarnings" -> null;
                case "unwrap" -> null;
                case "isWrapperFor" -> false;
                case "toString" -> "StubPostgresConnection";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
