# Changelog

Release index for Stats. Each entry links to the full release notes; this page only summarizes what changed and what it means for an upgrade.

| Version | Focus | Config version | Schema version | Paper API target |
| --- | --- | --- | --- | --- |
| [0.3.2](docs/RELEASE_0.3.2.md) | Packaging and licensing | 4 | 2 | 26.2 build 112 |
| [0.3.1](docs/RELEASE_0.3.1.md) | PostgreSQL connection fix | 4 | 2 | 26.2 build 111 |
| [0.3.0](docs/RELEASE_0.3.0.md) | Durable spool and release hardening | 4 | 2 | 26.2 build 111 |

Configuration version `4` and database schema version `2` have been stable across all three releases. Upgrading between them requires no configuration or schema migration.

## 0.3.2

MariaDB Connector/J moved out of the plugin JAR and into a `plugin.yml` library declaration, so Paper resolves and caches it separately. License notices are packaged under unique `META-INF` paths, and the Paper API compile target moved to build 112. No behavioral change to collection, commands, or storage.

The declaration is unconditional, so Paper may perform that resolution on first startup regardless of the configured backend. See [docs/RELEASE_0.3.2.md](docs/RELEASE_0.3.2.md).

## 0.3.1

PostgreSQL pools are built from a directly constructed `PGSimpleDataSource` rather than a driver-class lookup, which avoids a misleading driver-registration warning under Paper's plugin class loader. The JDBC URL used for durable spool identity is preserved exactly, so batches pending from 0.3.0 remain recoverable. See [docs/RELEASE_0.3.1.md](docs/RELEASE_0.3.1.md).

## 0.3.0

Introduced the durable spool: aggregate snapshots are written to a checksummed local file and atomically moved into place before transmission, then replayed on the next startup if a flush or shutdown write failed. Corrupt spool files are preserved and fail activation rather than being discarded. `/stats status` gained the durable pending batch count. See [docs/RELEASE_0.3.0.md](docs/RELEASE_0.3.0.md).
