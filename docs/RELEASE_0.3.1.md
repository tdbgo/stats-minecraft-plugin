# Stats 0.3.1

## Runtime fix

- PostgreSQL pools now use a directly constructed `PGSimpleDataSource` instead of Hikari's `driverClassName` lookup.
- This avoids the harmless but misleading registered-driver warning under Paper's plugin class loader.
- The JDBC URL used for durable spool identity is preserved exactly, so pending 0.3.0 batches remain recoverable.

## Compatibility

- Paper `26.2` build 111 or later in the 26.2 line
- Java 25
- Database schema version 2 and config version 4 are unchanged.
- Commands, permissions, table names, connection settings, and collection behavior are unchanged.
