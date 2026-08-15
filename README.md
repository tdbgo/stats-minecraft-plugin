# Stats

**Stats by PLAYCITY BLOCK**

[한국어](README.ko.md)

Stats is a server-only Paper plugin. It stores low-cardinality player activity data as session, hourly, and daily aggregates. The data is designed for dashboards and offline analysis.

## Recorded data

Stats records session duration, AFK time, deaths, kills, teleports, material groups, and normalized command categories. It does not record coordinates, chat messages, raw command lines, IP addresses, or inventory snapshots.

See [DATA_CATALOG.md](docs/DATA_CATALOG.md) for the complete collection scope.

## Requirements

- Minecraft and Paper 26.2 build 112
- Java 25
- SQLite, PostgreSQL, MySQL, or MariaDB

Only the Paper 26.2 server line is tested. Clients do not install this plugin.

## Installation

1. Place `Stats-0.3.2.jar` in the Paper `plugins` directory.
2. Start the server once. Stats creates its configuration with `setup.enabled: false`.
3. Review the database and collection settings.
4. Set `setup.enabled: true` and restart the server normally, or run `/stats reload`.

Paper downloads MariaDB Connector/J 3.5.2 from Maven Central on the first start and caches it as a separate plugin library. A fresh offline installation is not supported unless that artifact is already present in Paper's library cache.

## Data destinations and network behavior

SQLite data stays in the Stats plugin data directory. PostgreSQL, MySQL, and MariaDB data is sent only to the database host selected by the server administrator. Stats has no telemetry, update checker, analytics endpoint, or other HTTP service.

Paper may contact Maven Central to resolve the external MariaDB driver. Stats otherwise opens only the configured JDBC database connection.

## Commands

All commands require `stats.admin` or operator status.

- `/stats reload`
- `/stats status`
- `/stats db ping`
- `/stats db health`
- `/stats flush`

See [CONFIG.md](docs/CONFIG.md) and [OPERATIONS.md](docs/OPERATIONS.md) for configuration and operating procedures.

## Build

```text
./gradlew clean test build
```

The output is `build/libs/Stats-0.3.2.jar`.

## License

Project-authored source, tests, documentation, and resources are licensed under the [MIT License](LICENSE), with `PLAYCITY` as the legal copyright holder.

Third-party components keep their own licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the [licenses](licenses) directory. MariaDB Connector/J is not included in the Stats JAR.
