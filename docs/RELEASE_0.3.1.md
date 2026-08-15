# Stats 0.3.1

A runtime fix release. No configuration, schema, command, or collection change.

## Runtime fix

- PostgreSQL pools are built from a directly constructed `PGSimpleDataSource` instead of relying on a Hikari `driverClassName` lookup.
- This avoids a harmless but misleading registered-driver warning under Paper's plugin class loader.
- The JDBC URL used for durable spool identity is preserved exactly, so batches left pending by 0.3.0 remain recoverable.

## Compatibility

- Paper `26.2`, built against build 111. Java 25.
- Database schema version `2` and configuration version `4` are unchanged.
- Commands, permissions, table names, connection settings, and collection behavior are unchanged.

See [RELEASE_0.3.2.md](RELEASE_0.3.2.md) for the current release, or [../CHANGELOG.md](../CHANGELOG.md) for the full history.
