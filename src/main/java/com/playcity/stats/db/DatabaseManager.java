package com.playcity.stats.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Function;

public final class DatabaseManager {
    private final DbType dbType;
    private final SqlDialect dialect;
    private final String tablePrefix;
    private final int queryTimeoutSeconds;
    private final HikariDataSource dataSource;
    private final String storageIdentity;

    public DatabaseManager(JavaPlugin plugin, FileConfiguration config) {
        this(plugin.getDataFolder(), config, HikariDataSource::new);
    }

    DatabaseManager(File dataFolder, FileConfiguration config, Function<HikariConfig, HikariDataSource> poolFactory) {
        this.dbType = DbType.fromConfig(config.getString("database.type", "sqlite"));
        this.dialect = switch (dbType) {
            case POSTGRES -> SqlDialect.POSTGRES;
            case MYSQL -> SqlDialect.MYSQL;
            case SQLITE -> SqlDialect.SQLITE;
        };
        this.tablePrefix = normalizePrefix(config.getString("database.tablePrefix", "mstats_"));
        this.queryTimeoutSeconds = clamp(config.getInt("database.queryTimeoutSeconds", 30), 1, 300);
        PoolConfiguration pool = buildPoolConfiguration(dataFolder, config, dbType, queryTimeoutSeconds);
        this.dataSource = poolFactory.apply(pool.hikari());
        this.storageIdentity = dbType.name() + "|" + tablePrefix + "|" + pool.connectionUrl();
    }

    public SqlDialect dialect() {
        return dialect;
    }

    public DbType dbType() {
        return dbType;
    }

    public String tablePrefix() {
        return tablePrefix;
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public String storageIdentity() {
        return storageIdentity;
    }

    public void close() {
        try {
            dataSource.close();
        } catch (Exception ignored) {
        }
    }

    public void ensureSchema(String pluginVersion, boolean autoMigrate) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            SchemaManager.ensureSchema(connection, dialect, tablePrefix, autoMigrate);
            String currentVersion = readMeta(connection, "schema_version");
            if (currentVersion != null && !currentVersion.isBlank()) {
                try {
                    int parsed = Integer.parseInt(currentVersion);
                    if (parsed > SchemaManager.SCHEMA_VERSION) {
                        throw new SQLException("Database schema version " + parsed + " is newer than supported version " + SchemaManager.SCHEMA_VERSION);
                    }
                } catch (NumberFormatException e) {
                    throw new SQLException("Invalid schema_version metadata: " + currentVersion, e);
                }
            }
            writeMeta(connection, "schema_version", String.valueOf(SchemaManager.SCHEMA_VERSION));
            if (pluginVersion != null) {
                writeMeta(connection, "plugin_version", pluginVersion);
            }
        }
    }

    public long pingMillis() throws SQLException {
        Instant start = Instant.now();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement st = connection.prepareStatement("SELECT 1")) {
            st.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    // no-op
                }
            }
        }
        return Duration.between(start, Instant.now()).toMillis();
    }

    private String readMeta(Connection connection, String key) throws SQLException {
        String table = tablePrefix + "meta";
        String keyColumn = dialect == SqlDialect.MYSQL ? "`key`" : "key";
        try (PreparedStatement st = connection.prepareStatement(
            "SELECT value FROM " + table + " WHERE " + keyColumn + " = ?"
        )) {
            st.setString(1, key);
            st.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void writeMeta(Connection connection, String key, String value) throws SQLException {
        String table = tablePrefix + "meta";
        switch (dialect) {
            case POSTGRES -> {
                try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO " + table + " (key, value) VALUES (?, ?) " +
                        "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value"
                )) {
                    st.setQueryTimeout(queryTimeoutSeconds);
                    st.setString(1, key);
                    st.setString(2, value);
                    st.executeUpdate();
                }
            }
            case MYSQL -> {
                try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO " + table + " (`key`, `value`) VALUES (?, ?) " +
                        "ON DUPLICATE KEY UPDATE `value` = VALUES(`value`)"
                )) {
                    st.setQueryTimeout(queryTimeoutSeconds);
                    st.setString(1, key);
                    st.setString(2, value);
                    st.executeUpdate();
                }
            }
            case SQLITE -> {
                try (PreparedStatement st = connection.prepareStatement(
                    "INSERT INTO " + table + " (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
                )) {
                    st.setQueryTimeout(queryTimeoutSeconds);
                    st.setString(1, key);
                    st.setString(2, value);
                    st.executeUpdate();
                }
            }
        }
    }

    static PoolConfiguration buildPoolConfiguration(
        File dataFolder,
        FileConfiguration config,
        DbType dbType,
        int queryTimeoutSeconds
    ) {
        HikariConfig hikari = new HikariConfig();

        int maxPool = config.getInt("database.pool.maximumPoolSize", 2);
        int minIdle = config.getInt("database.pool.minimumIdle", 0);
        int boundedMaxPool = clamp(maxPool, 1, 32);
        hikari.setMaximumPoolSize(boundedMaxPool);
        hikari.setMinimumIdle(clamp(minIdle, 0, boundedMaxPool));
        hikari.setConnectionTimeout(config.getLong("database.pool.connectionTimeoutMs", 5000));
        hikari.setIdleTimeout(config.getLong("database.pool.idleTimeoutMs", 600_000));
        hikari.setMaxLifetime(config.getLong("database.pool.maxLifetimeMs", 1_800_000));

        String connectionUrl = switch (dbType) {
            case SQLITE -> {
                String fileName = config.getString("database.sqlite.file", "stats.db");
                File dbFile = new File(dataFolder, fileName);
                String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
                hikari.setJdbcUrl(url);
                hikari.setDriverClassName("org.sqlite.JDBC");
                hikari.setMaximumPoolSize(1);
                hikari.setMinimumIdle(0);
                hikari.setPoolName("Stats-SQLite");

                String journalMode = config.getString("database.sqlite.pragmas.journal_mode", "WAL");
                String synchronous = config.getString("database.sqlite.pragmas.synchronous", "NORMAL");
                String foreignKeys = config.getString("database.sqlite.pragmas.foreign_keys", "ON");
                int busyTimeoutMs = config.getInt("database.sqlite.busyTimeoutMs", 5000);

                hikari.addDataSourceProperty("journal_mode", journalMode);
                hikari.addDataSourceProperty("synchronous", synchronous);
                hikari.addDataSourceProperty("foreign_keys", foreignKeys);
                hikari.addDataSourceProperty("busy_timeout", Math.max(0, busyTimeoutMs));
                yield url;
            }
            case POSTGRES -> {
                String host = config.getString("database.host", "127.0.0.1");
                int port = config.getInt("database.port", 5432);
                String database = config.getString("database.database", "minecraft");
                String schema = config.getString("database.schema", "public");
                String sslMode = config.getString("database.ssl.mode", "prefer");
                boolean sslEnabled = config.getBoolean("database.ssl.enabled", false);

                String url = "jdbc:postgresql://" + host + ":" + port + "/" + database +
                    "?applicationName=Stats" +
                    "&currentSchema=" + schema +
                    "&connectTimeout=" + Math.max(1, config.getInt("database.pool.connectionTimeoutMs", 5000) / 1000) +
                    "&socketTimeout=" + queryTimeoutSeconds +
                    "&sslmode=" + (sslEnabled ? sslMode : "disable");

                PGSimpleDataSource postgres = new PGSimpleDataSource();
                postgres.setServerNames(new String[]{host});
                postgres.setPortNumbers(new int[]{port});
                postgres.setDatabaseName(database);
                postgres.setApplicationName("Stats");
                postgres.setCurrentSchema(schema);
                postgres.setConnectTimeout(Math.max(1, config.getInt("database.pool.connectionTimeoutMs", 5000) / 1000));
                postgres.setSocketTimeout(queryTimeoutSeconds);
                postgres.setSslMode(sslEnabled ? sslMode : "disable");
                hikari.setDataSource(postgres);
                hikari.setUsername(config.getString("database.username", "stats"));
                hikari.setPassword(config.getString("database.password", ""));
                hikari.setPoolName("Stats-Postgres");
                yield url;
            }
            case MYSQL -> {
                String host = config.getString("database.host", "127.0.0.1");
                int port = config.getInt("database.port", 3306);
                String database = config.getString("database.database", "minecraft");
                boolean sslEnabled = config.getBoolean("database.ssl.enabled", false);

                String url = "jdbc:mariadb://" + host + ":" + port + "/" + database +
                    "?useUnicode=true&characterEncoding=utf8" +
                    "&connectTimeout=" + config.getInt("database.pool.connectionTimeoutMs", 5000) +
                    "&socketTimeout=" + (queryTimeoutSeconds * 1000) +
                    "&useSSL=" + (sslEnabled ? "true" : "false");

                hikari.setJdbcUrl(url);
                hikari.setDriverClassName("org.mariadb.jdbc.Driver");
                hikari.setUsername(config.getString("database.username", "stats"));
                hikari.setPassword(config.getString("database.password", ""));
                hikari.setPoolName("Stats-MySQL");
                yield url;
            }
        };

        hikari.setAutoCommit(true);
        hikari.setInitializationFailTimeout(10_000);
        hikari.setValidationTimeout(5_000);

        return new PoolConfiguration(hikari, connectionUrl);
    }

    private static String normalizePrefix(String prefix) {
        String p = prefix == null ? "mstats_" : prefix.trim();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("database.tablePrefix must not be blank");
        }
        if (!p.endsWith("_")) {
            p = p + "_";
        }
        if (p.length() > 24 || !p.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                "database.tablePrefix must start with a letter/underscore, contain only letters, digits, underscores, and be at most 24 characters"
            );
        }
        return p;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    record PoolConfiguration(HikariConfig hikari, String connectionUrl) {}
}
