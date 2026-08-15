# Modrinth Publication Preparation

This file prepares Stats for a future Modrinth project. It does not authorize or record a Modrinth upload.

## Project metadata

| Field | Value |
| --- | --- |
| Project title | Stats |
| Byline | by PLAYCITY BLOCK |
| Project type | Plugin |
| Environment | Server required, client unsupported |
| Loader | Paper |
| Minecraft version | 26.2 |
| Java version | 25 |
| License | MIT |
| Source | https://github.com/tdbgo/stats-minecraft-plugin |
| Issues | https://github.com/tdbgo/stats-minecraft-plugin/issues |

## Verified facts for publication review

- Stats is a server-only Paper plugin for low-cardinality player activity aggregates.
- It stores session, hourly, and daily data for dashboards and offline analysis.
- It does not record coordinates, chat messages, raw command lines, IP addresses, or inventory snapshots.
- It does not provide telemetry, an update checker, or an external analytics service.
- SQLite stays local. Remote database data goes only to the administrator-selected JDBC host.
- Collection remains disabled until `setup.enabled` is set to `true`.
- Paper downloads and caches MariaDB Connector/J 3.5.2 from Maven Central as a separate plugin library.
- HikariCP, SQLite JDBC, and PostgreSQL JDBC are bundled with their notices.

The Modrinth summary and description are intentionally not drafted here. The current Content Rules prohibit project-page descriptions created by or derived from generative output. A publisher must write the final English summary and plain-text description independently and verify every statement against the source and release artifact.

## Version metadata for 0.3.2

| Field | Value |
| --- | --- |
| Version name | Stats 0.3.2 |
| Version number | 0.3.2 |
| Version type | Release |
| File | Stats-0.3.2.jar |
| Game version | 26.2 only |
| Loader | Paper only |
| Environment | Server required |

Required platform: Paper 26.2 build 112 with Java 25.

Automatically loaded dependency: `org.mariadb.jdbc:mariadb-java-client:3.5.2` through Paper's `plugin.yml` library resolver. Users do not install this driver manually.

## Publication checklist

- Re-read the current Modrinth Content Rules at https://modrinth.com/legal/rules.
- Confirm that the title contains only `Stats` and the summary does not repeat the title.
- Write the English summary and plain-text description independently. Do not copy or derive them from generated text.
- Select only plugin, server required, Paper, Minecraft 26.2, Java 25, and MIT.
- Add the public source and issue links above.
- Disclose the administrator-selected remote database behavior and Paper's Maven Central download.
- Review every available content disclosure field at publication time, including the `Contains AI-generated content` field, and answer from the actual project and publishing provenance.
- Declare every applicable dependency in the version dependency section. Keep the automatic Maven library disclosure in the description.
- Upload only the verified `Stats-0.3.2.jar` for this version. Do not use additional files for unrelated artifacts.
- Do not add an icon or gallery image without verified authorship and distribution rights. Do not use generated images.
- Recheck the JAR descriptor, game version, loader, environment, license, dependencies, notices, and SHA-256 immediately before upload.
