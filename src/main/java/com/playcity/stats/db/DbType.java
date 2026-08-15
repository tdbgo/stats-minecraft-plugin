package com.playcity.stats.db;

import java.util.Locale;

public enum DbType {
    SQLITE,
    POSTGRES,
    MYSQL;

    public static DbType fromConfig(String value) {
        if (value == null) {
            return SQLITE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "postgres", "postgresql", "pg" -> POSTGRES;
            case "mysql", "mariadb", "maria" -> MYSQL;
            case "sqlite", "sqlite3" -> SQLITE;
            default -> throw new IllegalArgumentException("Unsupported database.type: " + value);
        };
    }
}
