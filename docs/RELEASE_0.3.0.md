# Stats 0.3.0

## Release baseline

- Paper `26.2` build 111
- Java 25
- Gradle 9.6.1 wrapper
- Shadow 9.6.1

## Included

- A non-SNAPSHOT release version, with the descriptor and API versions aligned to it
- A durable spool for aggregate snapshots before database transmission, using a checksum and an atomic move
- Automatic replay on the next startup of batches left unwritten by a database failure or a failed shutdown flush
- Corrupt spool files preserved, with activation failing safely rather than discarding them
- A durable pending batch count in `/stats status`
- JUnit Platform launcher configuration for Gradle 9 and Java 25 test execution

Existing database schema, commands, permissions, and configuration keys are unchanged. `config-version` stays at `4`.

## Deliberately deferred at this release

These were considered and set aside. See the linked documents for their current status.

- **Raw command lines, full arguments, and per-block source records** — retained the existing normalized aggregate policy because of privacy, cardinality, and storage growth. See [COMMAND_NORMALIZATION.md](COMMAND_NORMALIZATION.md).
- **A continuous per-event write-ahead log** — would close the window of a forced kill between flushes, but requires file I/O on the event hot path and a storage design to match. See [FLUSHING.md](FLUSHING.md).
- **Risk detection and real-time alerting** — needs false-positive handling, permission and protected-region integration, and a 5-minute bucket design first. Kept as an analysis-layer topic; see [RISK_DETECTION.md](RISK_DETECTION.md).
- **Automatic configuration file rewriting** — needs comment-preserving YAML plus a backup and atomic-replacement policy, so the runtime default merge was kept. See [CONFIG_MIGRATION.md](CONFIG_MIGRATION.md).
- **Visualization, long-term rollups, and automated backfill** — kept out of the collector release as separate batch and web-layer scope. See [ANALYTICS_IDEAS.md](ANALYTICS_IDEAS.md) and [IMPORT_EXISTING_DATA.md](IMPORT_EXISTING_DATA.md).

All five remain unimplemented in 0.3.2.

See [../CHANGELOG.md](../CHANGELOG.md) for the full release history.
