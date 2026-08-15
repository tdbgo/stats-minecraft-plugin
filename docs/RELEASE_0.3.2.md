# Stats 0.3.2

Stats 0.3.2 targets Minecraft/Paper `26.2` build 112 with Java 25. It is a packaging and licensing release: no configuration key, table, column, command, permission, or collection behavior changes.

## Changes

- MariaDB Connector/J 3.5.2 is now declared in `plugin.yml` as a Paper library instead of being shaded into the plugin JAR.
- The release JAR contains no `org.mariadb` classes. The build fails if any appear.
- Project and bundled dependency license notices are packaged under unique `META-INF` paths.
- The Paper API compile target moved from build 111 to build 112.

## Compatibility

Configuration version `4` and database schema version `2` are unchanged, as are table names, columns, commands, permissions, and collection behavior. Existing SQLite, PostgreSQL, MySQL, and MariaDB configurations remain valid, and no migration step is required when coming from 0.3.1.

## MariaDB driver resolution

Because MariaDB Connector/J is no longer bundled, Paper resolves `org.mariadb.jdbc:mariadb-java-client:3.5.2` from Maven Central and caches it in its own library store.

- The declaration is unconditional. It does not depend on `database.type`, so this resolution can occur even on SQLite and PostgreSQL servers.
- A server whose Paper library cache already holds that exact artifact starts without a download.
- A server without it needs Maven Central reachable on that start.
- Once cached, later startups do not download it again.

A fully offline first installation requires the artifact to be present in Paper's library cache beforehand.

## Upgrade

1. Stop the server normally. Do not use hot-reload tooling.
2. Back up the database.
3. Replace the previous Stats JAR with `Stats-0.3.2.jar`.
4. Start the server normally.
5. Confirm that `/stats status` reports version `0.3.2` and the expected database type and table prefix.

## Verification in this release

The `verifyDistribution` build task, wired into `check`, asserts that the plugin main class is present, that MariaDB Connector/J and the Paper/Bukkit API are absent from the JAR, and that every required license notice is packaged.

## Links

- Release: <https://github.com/tdbgo/stats-minecraft-plugin/releases/tag/v0.3.2>
- Issues: <https://github.com/tdbgo/stats-minecraft-plugin/issues>
- Release history: <https://github.com/tdbgo/stats-minecraft-plugin/blob/main/CHANGELOG.md>
