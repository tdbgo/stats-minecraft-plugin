# Stats 0.3.2

Stats 0.3.2 targets Minecraft and Paper 26.2 build 112 with Java 25.

## Changes

- MariaDB Connector/J 3.5.2 is now loaded as a separate Paper plugin library.
- The release JAR no longer contains `org.mariadb` classes.
- Project and bundled dependency license notices are included under unique `META-INF` paths.
- The Paper API compile target is updated from build 111 to build 112.

## Compatibility

Configuration version 4, database schema version 2, table names, columns, commands, permissions, and collection behavior are unchanged. Existing SQLite, PostgreSQL, MySQL, and MariaDB configurations remain valid.

Paper downloads the MariaDB driver from Maven Central on the first startup. A fresh offline installation requires that exact artifact to be present in Paper's library cache. Later cached starts do not require another download.

## Installation

1. Stop the server normally.
2. Replace the previous Stats JAR with `Stats-0.3.2.jar`.
3. Start the server normally.
4. Confirm that Stats reports version 0.3.2 and the configured database type.

Do not use hot reload tools.
