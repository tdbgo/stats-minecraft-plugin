# Modrinth Publication Preparation

This document prepares the Stats 0.3.2 Modrinth listing. It is a preparation reference. It does not authorize, schedule, or record an upload.

Every value below must be confirmed against the built artifact and the repository immediately before submission.

## 1. Project metadata

| Field | Value |
| --- | --- |
| Project title | Stats |
| Byline | by PLAYCITY BLOCK |
| Project type | Plugin |
| Environment — client | Unsupported |
| Environment — server | Required |
| Loader | Paper |
| Game version | 26.2 |
| Java version | 25 |
| License | MIT (project-authored code) |
| Source | https://github.com/tdbgo/stats-minecraft-plugin |
| Issues | https://github.com/tdbgo/stats-minecraft-plugin/issues |

Select only Paper as the loader and only 26.2 as the game version. Paper 26.2 build 112 is the verified compile and test target; no later or earlier build is verified, so do not widen the version selection.

Stats is a server-side plugin. Players connecting to a server running Stats install nothing.

## 2. Version metadata for 0.3.2

| Field | Value |
| --- | --- |
| Version name | Stats 0.3.2 |
| Version number | 0.3.2 |
| Version type | Release |
| File | `Stats-0.3.2.jar` |
| Game version | 26.2 only |
| Loader | Paper only |
| Environment | Server required, client unsupported |

Required platform: Paper 26.2 build 112 with Java 25.

Upload only the verified `Stats-0.3.2.jar`. Do not attach unrelated artifacts to this version.

## 3. Submission description copy

The following English copy is prepared for the project page. Verify each sentence against the release artifact before pasting, and enable the AI-generated content disclosure described in section 7.

### Summary (short description)

> Server-side activity collector for Paper. Aggregates playtime, sessions, commands, blocks, deaths, and travel into SQLite, PostgreSQL, or MySQL/MariaDB for your own dashboards and queries.

### Description (project page body)

> **Stats** is a server-only Paper plugin that records player activity as queryable aggregates. It is a collector, not a dashboard: it writes a fixed, documented set of counters to a database you control, and you build reports on top with ordinary SQL.
>
> **How it works**
>
> Events are counted in memory. A single dedicated I/O thread writes the accumulated batch to the database every 300 seconds by default, instead of writing once per event. When there is nothing pending, no database connection is requested at all.
>
> Data volume is bounded by design. Commands are normalized to a canonical key plus a short allow-listed variant. Block materials collapse into roughly two dozen groups. Repeated block events with the same player, day, material group, and action merge into one row instead of producing one row per event.
>
> Every non-empty batch receives a UUID, is written to a checksummed local spool file before transmission, and is claimed in an ingest ledger inside the same transaction as the fact rows. A failed flush retries with the same batch id — on the next flush or the next server start — without double-counting.
>
> **What it stores**
>
> Player identity (UUID, first and last seen, last known name); sessions with duration, AFK seconds, and join/quit worlds; playtime and AFK seconds per hour; active minutes per hour; chat message and character counts per hour; normalized command execution counts; block place and break totals plus per-day material-group counts; deaths by damage cause; PvP and mob kill counts; sampled travel distance; and teleport counts with same-world distance.
>
> **What it never stores**
>
> Coordinates, chat message bodies, raw command lines or arguments, IP addresses, client brand, locale, inventory snapshots, and individual block events. Positions are read in memory to compute a distance and discarded immediately.
>
> **Storage and network**
>
> Choose SQLite, PostgreSQL, or MySQL/MariaDB. All three share the same logical schema. Stats has no telemetry, no update checker, no metrics reporter, and no HTTP endpoint; the only connection it opens is the JDBC connection to the database the server operator configures. See the dependency note below for Paper's separate library resolution.
>
> **Safe first start**
>
> On first run Stats writes its configuration and stops. No database is contacted and nothing is collected until the operator reviews the settings and sets `setup.enabled: true`.
>
> Requires Paper 26.2 (build 112 verified) and Java 25. Full documentation, configuration reference, and data catalog are in the linked repository.

### Required dependency disclosure

Include this paragraph verbatim in the description body:

> **Automatic library download.** The plugin descriptor declares `org.mariadb.jdbc:mariadb-java-client:3.5.2` as a Paper library. MariaDB Connector/J is not bundled inside the plugin JAR. Paper resolves this coordinate from Maven Central and caches it in its own library store. The declaration is unconditional, so this resolution can occur on first startup regardless of which database backend is selected. Once cached, later startups do not download it again. A fully offline first installation requires that artifact to already be present in Paper's library cache.

## 4. Data and privacy disclosures

State these plainly on the project page:

- Stats is server-side only. Clients install nothing and are not contacted.
- Stats contains no telemetry, no update checker, and no analytics endpoint.
- With SQLite, Stats opens no JDBC network connection and keeps collected data in the plugin's data directory. Paper may still contact Maven Central to resolve the declared MariaDB library.
- With PostgreSQL or MySQL/MariaDB, aggregate data is transmitted only to the JDBC host the server operator configures. Stats does not select or contact an additional data destination.
- Collection is disabled until the operator sets `setup.enabled: true`.
- Coordinates, chat bodies, raw command lines and arguments, IP addresses, client brand, locale, and inventory contents are never stored.
- Undelivered aggregate batches are held in a local spool directory until the database accepts them.

## 5. Dependencies

| Component | Version | Relationship |
| --- | --- | --- |
| MariaDB Connector/J | 3.5.2 | Declared Paper library, resolved from Maven Central, not bundled |
| HikariCP | 5.1.0 | Bundled in the JAR with its notice |
| SQLite JDBC | 3.46.1.3 | Bundled in the JAR with its notice |
| PostgreSQL JDBC | 42.7.5 | Bundled in the JAR with its notice |

There are no Modrinth project dependencies. Stats requires no other plugin.

Project-authored code is MIT, with `PLAYCITY` as the copyright holder. Bundled third-party components retain their own licenses. MariaDB Connector/J is LGPL-2.1-or-later and stays outside the Stats JAR.

## 6. Content rules position

Under the official Modrinth Content Rules last modified 2026-08-13:

- **Section 6.1** requires the `Contains AI-generated content` disclosure when a project-page description or the publishing process relies on generative assistance. Assisted text is permitted when that disclosure is enabled. The description copy in section 3 was prepared with generative assistance, so the disclosure must be enabled for this listing.
- **Section 6.2** prohibits generated or generated-derived images anywhere on the project page, and prohibits projects whose content is primarily or entirely generated output. No generated or generated-derived icon, gallery image, banner, or any other project-page image may be used. Confirm the project's overall provenance against the current rule before publication.

Rules change. Re-read them at publication time rather than relying on this summary.

## 7. Pre-publication checklist

**Rules and disclosure**

- [ ] Re-read the current Content Rules at <https://modrinth.com/legal/rules> and confirm this document still matches them.
- [ ] Set `Contains AI-generated content` to **enabled**, because the prepared page description used generative assistance.
- [ ] Confirm no icon, gallery image, banner, or other project-page image is generated or generated-derived. Use only imagery with verified authorship and distribution rights, or none at all.
- [ ] Review every other content disclosure field and answer from the project's actual provenance.

**Metadata**

- [ ] Title contains only `Stats`.
- [ ] Summary does not repeat the title.
- [ ] Project type is Plugin; server is Required and client is Unsupported.
- [ ] Loader is Paper only. Game version is 26.2 only.
- [ ] License is set to MIT.
- [ ] Source and issue links from section 1 are present and resolve.

**Content**

- [ ] Description matches section 3, with every claim verified against the artifact.
- [ ] The automatic library download paragraph is present and unaltered in meaning.
- [ ] The remote database and data-collection disclosures from section 4 are present.
- [ ] No performance, security, or compatibility guarantee appears that the repository does not support.
- [ ] No compatibility claim extends beyond Paper 26.2 build 112 and Java 25.

**Version and artifact**

- [ ] Version number is `0.3.2` and version type is Release.
- [ ] Only `Stats-0.3.2.jar` is attached.
- [ ] The JAR descriptor reports version `0.3.2` and `api-version: "26.2"`.
- [ ] The JAR contains no MariaDB Connector/J classes and no bundled Paper or Bukkit API.
- [ ] Required license notices are present under `META-INF`.
- [ ] The SHA-256 of the uploaded file matches the verified build output.
- [ ] Declared dependencies match section 5.
