package com.playcity.stats.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

public final class SchemaManager {
    public static final int SCHEMA_VERSION = 2;

    private SchemaManager() {}

    public static void ensureSchema(Connection connection, SqlDialect dialect, String prefix, boolean autoMigrate) throws SQLException {
        if (prefix == null || prefix.isBlank()) {
            prefix = "mstats_";
        }

        try (Statement st = connection.createStatement()) {
            for (String sql : createStatements(dialect, prefix)) {
                st.execute(sql);
            }
        }

        ensureTeleportColumns(connection, dialect, prefix, autoMigrate);
    }

    private static void ensureTeleportColumns(Connection connection, SqlDialect dialect, String prefix, boolean autoMigrate) throws SQLException {
        String hourTable = prefix + "fact_player_hour";
        String dayTable = prefix + "fact_player_day";
        String integerType = dialect == SqlDialect.MYSQL ? "INT" : "INTEGER";

        ensureColumn(connection, hourTable, "teleport_count", integerType + " NOT NULL DEFAULT 0", autoMigrate);
        ensureColumn(connection, hourTable, "teleport_distance_m", integerType + " NOT NULL DEFAULT 0", autoMigrate);
        ensureColumn(connection, dayTable, "teleport_count", integerType + " NOT NULL DEFAULT 0", autoMigrate);
        ensureColumn(connection, dayTable, "teleport_distance_m", integerType + " NOT NULL DEFAULT 0", autoMigrate);
    }

    private static void ensureColumn(Connection connection, String table, String column, String definition, boolean autoMigrate) throws SQLException {
        if (hasColumn(connection, table, column)) {
            return;
        }
        if (!autoMigrate) {
            throw new SQLException("Schema migration required: missing " + table + "." + column);
        }
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if (column.equalsIgnoreCase(meta.getColumnName(i))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String[] createStatements(SqlDialect dialect, String p) {
        String meta = p + "meta";
        String ingestBatch = p + "ingest_batch";
        String dimPlayer = p + "dim_player";
        String dimCommand = p + "dim_command";
        String dimCommandVariant = p + "dim_command_variant";
        String factSession = p + "fact_session";
        String factPlayerHour = p + "fact_player_hour";
        String factPlayerDay = p + "fact_player_day";
        String factCommandHour = p + "fact_command_hour";
        String factCommandDay = p + "fact_command_day";
        String factBlockGroupDay = p + "fact_block_group_day";
        String factDeathDay = p + "fact_death_day";

        return switch (dialect) {
            case POSTGRES -> new String[]{
                "CREATE TABLE IF NOT EXISTS " + meta + " (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + ingestBatch + " (" +
                    "batch_id UUID PRIMARY KEY," +
                    "created_at TIMESTAMPTZ NOT NULL" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_ingest_batch_created_at ON " + ingestBatch + " (created_at)",

                "CREATE TABLE IF NOT EXISTS " + dimPlayer + " (" +
                    "player_uuid UUID PRIMARY KEY," +
                    "first_seen_at TIMESTAMPTZ NOT NULL," +
                    "last_seen_at TIMESTAMPTZ NOT NULL," +
                    "last_known_name VARCHAR(32)" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_dim_player_last_seen_at ON " + dimPlayer + " (last_seen_at)",

                "CREATE TABLE IF NOT EXISTS " + dimCommand + " (" +
                    "command_id BIGSERIAL PRIMARY KEY," +
                    "command_key TEXT NOT NULL UNIQUE," +
                    "family TEXT," +
                    "notes TEXT" +
                ")",
                "CREATE TABLE IF NOT EXISTS " + dimCommandVariant + " (" +
                    "variant_id BIGSERIAL PRIMARY KEY," +
                    "command_id BIGINT NOT NULL REFERENCES " + dimCommand + "(command_id) ON DELETE CASCADE," +
                    "variant_key TEXT NOT NULL," +
                    "UNIQUE(command_id, variant_key)" +
                ")",

                "CREATE TABLE IF NOT EXISTS " + factSession + " (" +
                    "session_id BIGSERIAL PRIMARY KEY," +
                    "player_uuid UUID NOT NULL REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE," +
                    "join_at TIMESTAMPTZ NOT NULL," +
                    "quit_at TIMESTAMPTZ," +
                    "duration_sec INTEGER," +
                    "afk_sec INTEGER," +
                    "join_world TEXT," +
                    "quit_world TEXT," +
                    "ip_hash BYTEA," +
                    "client_brand TEXT," +
                    "locale TEXT" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_session_player_join ON " + factSession + " (player_uuid, join_at)",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_session_join_at ON " + factSession + " (join_at)",

                "CREATE TABLE IF NOT EXISTS " + factPlayerHour + " (" +
                    "player_uuid UUID NOT NULL REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE," +
                    "hour_ts TIMESTAMPTZ NOT NULL," +
                    "playtime_sec INTEGER NOT NULL DEFAULT 0," +
                    "afk_sec INTEGER NOT NULL DEFAULT 0," +
                    "active_minutes SMALLINT NOT NULL DEFAULT 0," +
                    "chat_messages INTEGER NOT NULL DEFAULT 0," +
                    "chat_chars INTEGER NOT NULL DEFAULT 0," +
                    "commands_total INTEGER NOT NULL DEFAULT 0," +
                    "blocks_placed_total INTEGER NOT NULL DEFAULT 0," +
                    "blocks_broken_total INTEGER NOT NULL DEFAULT 0," +
                    "distance_m INTEGER NOT NULL DEFAULT 0," +
                    "teleport_count INTEGER NOT NULL DEFAULT 0," +
                    "teleport_distance_m INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(player_uuid, hour_ts)" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_player_hour_hour_ts ON " + factPlayerHour + " (hour_ts)",

                "CREATE TABLE IF NOT EXISTS " + factPlayerDay + " (" +
                    "player_uuid UUID NOT NULL REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE," +
                    "day DATE NOT NULL," +
                    "playtime_sec INTEGER NOT NULL DEFAULT 0," +
                    "sessions INTEGER NOT NULL DEFAULT 0," +
                    "deaths INTEGER NOT NULL DEFAULT 0," +
                    "kills_pvp INTEGER NOT NULL DEFAULT 0," +
                    "kills_mob INTEGER NOT NULL DEFAULT 0," +
                    "teleport_count INTEGER NOT NULL DEFAULT 0," +
                    "teleport_distance_m INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(player_uuid, day)" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_player_day_day ON " + factPlayerDay + " (day)",

                "CREATE TABLE IF NOT EXISTS " + factCommandHour + " (" +
                    "player_uuid UUID NOT NULL REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE," +
                    "hour_ts TIMESTAMPTZ NOT NULL," +
                    "variant_id BIGINT NOT NULL REFERENCES " + dimCommandVariant + "(variant_id) ON DELETE RESTRICT," +
                    "count INTEGER NOT NULL," +
                    "PRIMARY KEY(player_uuid, hour_ts, variant_id)" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_command_hour_hour_variant ON " + factCommandHour + " (hour_ts, variant_id)",

                "CREATE TABLE IF NOT EXISTS " + factCommandDay + " (" +
                    "player_uuid UUID NOT NULL REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE," +
                    "day DATE NOT NULL," +
                    "variant_id BIGINT NOT NULL REFERENCES " + dimCommandVariant + "(variant_id) ON DELETE RESTRICT," +
                    "count INTEGER NOT NULL," +
                    "PRIMARY KEY(player_uuid, day, variant_id)" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_command_day_day_variant ON " + factCommandDay + " (day, variant_id)",

                "CREATE TABLE IF NOT EXISTS " + factBlockGroupDay + " (" +
                    "player_uuid UUID NOT NULL REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE," +
                    "day DATE NOT NULL," +
                    "group_key VARCHAR(64) NOT NULL," +
                    "action SMALLINT NOT NULL," +
                    "count INTEGER NOT NULL," +
                    "PRIMARY KEY(player_uuid, day, group_key, action)" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_block_group_day_day ON " + factBlockGroupDay + " (day)",

                "CREATE TABLE IF NOT EXISTS " + factDeathDay + " (" +
                    "player_uuid UUID NOT NULL REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE," +
                    "day DATE NOT NULL," +
                    "cause VARCHAR(64) NOT NULL," +
                    "count INTEGER NOT NULL," +
                    "PRIMARY KEY(player_uuid, day, cause)" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_death_day_day ON " + factDeathDay + " (day)"
            };
            case MYSQL -> new String[]{
                "CREATE TABLE IF NOT EXISTS " + meta + " (`key` VARCHAR(64) NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS " + ingestBatch + " (" +
                    "batch_id BINARY(16) NOT NULL," +
                    "created_at DATETIME(3) NOT NULL," +
                    "PRIMARY KEY(batch_id)," +
                    "KEY " + p + "idx_ingest_batch_created_at (created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

                "CREATE TABLE IF NOT EXISTS " + dimPlayer + " (" +
                    "player_uuid BINARY(16) NOT NULL," +
                    "first_seen_at DATETIME(3) NOT NULL," +
                    "last_seen_at DATETIME(3) NOT NULL," +
                    "last_known_name VARCHAR(32)," +
                    "PRIMARY KEY(player_uuid)," +
                    "KEY " + p + "idx_dim_player_last_seen_at (last_seen_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

                "CREATE TABLE IF NOT EXISTS " + dimCommand + " (" +
                    "command_id BIGINT NOT NULL AUTO_INCREMENT," +
                    "command_key VARCHAR(255) NOT NULL," +
                    "family VARCHAR(255)," +
                    "notes TEXT," +
                    "PRIMARY KEY(command_id)," +
                    "UNIQUE KEY " + p + "uk_dim_command_command_key (command_key)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

                "CREATE TABLE IF NOT EXISTS " + dimCommandVariant + " (" +
                    "variant_id BIGINT NOT NULL AUTO_INCREMENT," +
                    "command_id BIGINT NOT NULL," +
                    "variant_key VARCHAR(255) NOT NULL," +
                    "PRIMARY KEY(variant_id)," +
                    "UNIQUE KEY " + p + "uk_dim_command_variant (command_id, variant_key)," +
                    "CONSTRAINT " + p + "fk_dim_command_variant_command FOREIGN KEY(command_id) REFERENCES " + dimCommand + "(command_id) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

                "CREATE TABLE IF NOT EXISTS " + factSession + " (" +
                    "session_id BIGINT NOT NULL AUTO_INCREMENT," +
                    "player_uuid BINARY(16) NOT NULL," +
                    "join_at DATETIME(3) NOT NULL," +
                    "quit_at DATETIME(3)," +
                    "duration_sec INT," +
                    "afk_sec INT," +
                    "join_world VARCHAR(255)," +
                    "quit_world VARCHAR(255)," +
                    "ip_hash VARBINARY(64)," +
                    "client_brand VARCHAR(255)," +
                    "locale VARCHAR(64)," +
                    "PRIMARY KEY(session_id)," +
                    "KEY " + p + "idx_fact_session_player_join (player_uuid, join_at)," +
                    "KEY " + p + "idx_fact_session_join_at (join_at)," +
                    "CONSTRAINT " + p + "fk_fact_session_player FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

                "CREATE TABLE IF NOT EXISTS " + factPlayerHour + " (" +
                    "player_uuid BINARY(16) NOT NULL," +
                    "hour_ts DATETIME(3) NOT NULL," +
                    "playtime_sec INT NOT NULL DEFAULT 0," +
                    "afk_sec INT NOT NULL DEFAULT 0," +
                    "active_minutes SMALLINT NOT NULL DEFAULT 0," +
                    "chat_messages INT NOT NULL DEFAULT 0," +
                    "chat_chars INT NOT NULL DEFAULT 0," +
                    "commands_total INT NOT NULL DEFAULT 0," +
                    "blocks_placed_total INT NOT NULL DEFAULT 0," +
                    "blocks_broken_total INT NOT NULL DEFAULT 0," +
                    "distance_m INT NOT NULL DEFAULT 0," +
                    "teleport_count INT NOT NULL DEFAULT 0," +
                    "teleport_distance_m INT NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(player_uuid, hour_ts)," +
                    "KEY " + p + "idx_fact_player_hour_hour_ts (hour_ts)," +
                    "CONSTRAINT " + p + "fk_fact_player_hour_player FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

                "CREATE TABLE IF NOT EXISTS " + factPlayerDay + " (" +
                    "player_uuid BINARY(16) NOT NULL," +
                    "day DATE NOT NULL," +
                    "playtime_sec INT NOT NULL DEFAULT 0," +
                    "sessions INT NOT NULL DEFAULT 0," +
                    "deaths INT NOT NULL DEFAULT 0," +
                    "kills_pvp INT NOT NULL DEFAULT 0," +
                    "kills_mob INT NOT NULL DEFAULT 0," +
                    "teleport_count INT NOT NULL DEFAULT 0," +
                    "teleport_distance_m INT NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(player_uuid, day)," +
                    "KEY " + p + "idx_fact_player_day_day (day)," +
                    "CONSTRAINT " + p + "fk_fact_player_day_player FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

                "CREATE TABLE IF NOT EXISTS " + factCommandHour + " (" +
                    "player_uuid BINARY(16) NOT NULL," +
                    "hour_ts DATETIME(3) NOT NULL," +
                    "variant_id BIGINT NOT NULL," +
                    "count INT NOT NULL," +
                    "PRIMARY KEY(player_uuid, hour_ts, variant_id)," +
                    "KEY " + p + "idx_fact_command_hour_hour_variant (hour_ts, variant_id)," +
                    "CONSTRAINT " + p + "fk_fact_command_hour_player FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE," +
                    "CONSTRAINT " + p + "fk_fact_command_hour_variant FOREIGN KEY(variant_id) REFERENCES " + dimCommandVariant + "(variant_id) ON DELETE RESTRICT" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

                "CREATE TABLE IF NOT EXISTS " + factCommandDay + " (" +
                    "player_uuid BINARY(16) NOT NULL," +
                    "day DATE NOT NULL," +
                    "variant_id BIGINT NOT NULL," +
                    "count INT NOT NULL," +
                    "PRIMARY KEY(player_uuid, day, variant_id)," +
                    "KEY " + p + "idx_fact_command_day_day_variant (day, variant_id)," +
                    "CONSTRAINT " + p + "fk_fact_command_day_player FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE," +
                    "CONSTRAINT " + p + "fk_fact_command_day_variant FOREIGN KEY(variant_id) REFERENCES " + dimCommandVariant + "(variant_id) ON DELETE RESTRICT" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

                "CREATE TABLE IF NOT EXISTS " + factBlockGroupDay + " (" +
                    "player_uuid BINARY(16) NOT NULL," +
                    "day DATE NOT NULL," +
                    "group_key VARCHAR(64) NOT NULL," +
                    "action SMALLINT NOT NULL," +
                    "count INT NOT NULL," +
                    "PRIMARY KEY(player_uuid, day, group_key, action)," +
                    "KEY " + p + "idx_fact_block_group_day_day (day)," +
                    "CONSTRAINT " + p + "fk_fact_block_group_day_player FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

                "CREATE TABLE IF NOT EXISTS " + factDeathDay + " (" +
                    "player_uuid BINARY(16) NOT NULL," +
                    "day DATE NOT NULL," +
                    "cause VARCHAR(64) NOT NULL," +
                    "count INT NOT NULL," +
                    "PRIMARY KEY(player_uuid, day, cause)," +
                    "KEY " + p + "idx_fact_death_day_day (day)," +
                    "CONSTRAINT " + p + "fk_fact_death_day_player FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            };
            case SQLITE -> new String[]{
                "PRAGMA foreign_keys = ON",
                "CREATE TABLE IF NOT EXISTS " + meta + " (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + ingestBatch + " (" +
                    "batch_id BLOB NOT NULL PRIMARY KEY," +
                    "created_at TEXT NOT NULL" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_ingest_batch_created_at ON " + ingestBatch + " (created_at)",

                "CREATE TABLE IF NOT EXISTS " + dimPlayer + " (" +
                    "player_uuid BLOB NOT NULL," +
                    "first_seen_at TEXT NOT NULL," +
                    "last_seen_at TEXT NOT NULL," +
                    "last_known_name TEXT," +
                    "PRIMARY KEY(player_uuid)" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_dim_player_last_seen_at ON " + dimPlayer + " (last_seen_at)",

                "CREATE TABLE IF NOT EXISTS " + dimCommand + " (" +
                    "command_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "command_key TEXT NOT NULL UNIQUE," +
                    "family TEXT," +
                    "notes TEXT" +
                ")",
                "CREATE TABLE IF NOT EXISTS " + dimCommandVariant + " (" +
                    "variant_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "command_id INTEGER NOT NULL," +
                    "variant_key TEXT NOT NULL," +
                    "UNIQUE(command_id, variant_key)," +
                    "FOREIGN KEY(command_id) REFERENCES " + dimCommand + "(command_id) ON DELETE CASCADE" +
                ")",

                "CREATE TABLE IF NOT EXISTS " + factSession + " (" +
                    "session_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "player_uuid BLOB NOT NULL," +
                    "join_at TEXT NOT NULL," +
                    "quit_at TEXT," +
                    "duration_sec INTEGER," +
                    "afk_sec INTEGER," +
                    "join_world TEXT," +
                    "quit_world TEXT," +
                    "ip_hash BLOB," +
                    "client_brand TEXT," +
                    "locale TEXT," +
                    "FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_session_player_join ON " + factSession + " (player_uuid, join_at)",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_session_join_at ON " + factSession + " (join_at)",

                "CREATE TABLE IF NOT EXISTS " + factPlayerHour + " (" +
                    "player_uuid BLOB NOT NULL," +
                    "hour_ts TEXT NOT NULL," +
                    "playtime_sec INTEGER NOT NULL DEFAULT 0," +
                    "afk_sec INTEGER NOT NULL DEFAULT 0," +
                    "active_minutes INTEGER NOT NULL DEFAULT 0," +
                    "chat_messages INTEGER NOT NULL DEFAULT 0," +
                    "chat_chars INTEGER NOT NULL DEFAULT 0," +
                    "commands_total INTEGER NOT NULL DEFAULT 0," +
                    "blocks_placed_total INTEGER NOT NULL DEFAULT 0," +
                    "blocks_broken_total INTEGER NOT NULL DEFAULT 0," +
                    "distance_m INTEGER NOT NULL DEFAULT 0," +
                    "teleport_count INTEGER NOT NULL DEFAULT 0," +
                    "teleport_distance_m INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(player_uuid, hour_ts)," +
                    "FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_player_hour_hour_ts ON " + factPlayerHour + " (hour_ts)",

                "CREATE TABLE IF NOT EXISTS " + factPlayerDay + " (" +
                    "player_uuid BLOB NOT NULL," +
                    "day TEXT NOT NULL," +
                    "playtime_sec INTEGER NOT NULL DEFAULT 0," +
                    "sessions INTEGER NOT NULL DEFAULT 0," +
                    "deaths INTEGER NOT NULL DEFAULT 0," +
                    "kills_pvp INTEGER NOT NULL DEFAULT 0," +
                    "kills_mob INTEGER NOT NULL DEFAULT 0," +
                    "teleport_count INTEGER NOT NULL DEFAULT 0," +
                    "teleport_distance_m INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(player_uuid, day)," +
                    "FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_player_day_day ON " + factPlayerDay + " (day)",

                "CREATE TABLE IF NOT EXISTS " + factCommandHour + " (" +
                    "player_uuid BLOB NOT NULL," +
                    "hour_ts TEXT NOT NULL," +
                    "variant_id INTEGER NOT NULL," +
                    "count INTEGER NOT NULL," +
                    "PRIMARY KEY(player_uuid, hour_ts, variant_id)," +
                    "FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE," +
                    "FOREIGN KEY(variant_id) REFERENCES " + dimCommandVariant + "(variant_id) ON DELETE RESTRICT" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_command_hour_hour_variant ON " + factCommandHour + " (hour_ts, variant_id)",

                "CREATE TABLE IF NOT EXISTS " + factCommandDay + " (" +
                    "player_uuid BLOB NOT NULL," +
                    "day TEXT NOT NULL," +
                    "variant_id INTEGER NOT NULL," +
                    "count INTEGER NOT NULL," +
                    "PRIMARY KEY(player_uuid, day, variant_id)," +
                    "FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE," +
                    "FOREIGN KEY(variant_id) REFERENCES " + dimCommandVariant + "(variant_id) ON DELETE RESTRICT" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_command_day_day_variant ON " + factCommandDay + " (day, variant_id)",

                "CREATE TABLE IF NOT EXISTS " + factBlockGroupDay + " (" +
                    "player_uuid BLOB NOT NULL," +
                    "day TEXT NOT NULL," +
                    "group_key TEXT NOT NULL," +
                    "action INTEGER NOT NULL," +
                    "count INTEGER NOT NULL," +
                    "PRIMARY KEY(player_uuid, day, group_key, action)," +
                    "FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_block_group_day_day ON " + factBlockGroupDay + " (day)",

                "CREATE TABLE IF NOT EXISTS " + factDeathDay + " (" +
                    "player_uuid BLOB NOT NULL," +
                    "day TEXT NOT NULL," +
                    "cause TEXT NOT NULL," +
                    "count INTEGER NOT NULL," +
                    "PRIMARY KEY(player_uuid, day, cause)," +
                    "FOREIGN KEY(player_uuid) REFERENCES " + dimPlayer + "(player_uuid) ON DELETE CASCADE" +
                ")",
                "CREATE INDEX IF NOT EXISTS " + p + "idx_fact_death_day_day ON " + factDeathDay + " (day)"
            };
        };
    }
}
