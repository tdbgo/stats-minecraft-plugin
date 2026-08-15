# Third-Party Notices

This document describes third-party components used by Stats 0.3.1. It is an engineering compliance record, not legal advice.

The root `LICENSE` applies only to Stats files authored for this project unless a file carries a separate notice. It does not replace the licenses of the components listed below.

## Publication and binary boundary

This source repository does not publish a compiled Stats JAR. The default `shadowJar` build does not relocate dependencies; it merges dependency classes and service descriptors into one fat JAR. The inspected 0.3.1 JAR has SHA-256 `21fcde03e235f50db26412d01368e05818e29392c25cffd9e2c7021d7ce0c6e3`.

The fat JAR contains third-party classes, but not every upstream license is retained under a unique JAR entry. Therefore, do not redistribute the fat JAR by itself. A distributor must accompany it with the applicable notices and license texts and independently satisfy each license's binary-distribution terms.

MariaDB Connector/J 3.5.2 is included in the default fat JAR and is licensed LGPL-2.1-or-later. GNU LGPL 2.1 section 6 requires prominent notice, a copy of the license, and one of its source and relinking mechanisms when distributing a combined work. Publishing the Stats source and Gradle build scripts provides the application-side material needed to rebuild, but does not by itself provide MariaDB Connector/J's complete corresponding source. A distributor of the fat JAR must also provide or offer equivalent access to the exact MariaDB Connector/J source and preserve the user's ability to replace or relink an interface-compatible modified library. The exact upstream source is available from the `3.5.2` tag and Maven Central source artifact.

Moving MariaDB Connector/J to Paper's external `libraries` mechanism would avoid embedding it, but that changes startup, offline-cache, and class-loader behavior. It is intentionally deferred to a separately versioned and runtime-tested release rather than changing the verified 0.3.1 artifact silently.

## Bundled in the default Stats JAR

| Component | Version | License | Included material and notice |
| --- | --- | --- | --- |
| HikariCP | 5.1.0 | Apache-2.0 | `com.zaxxer` classes. See `licenses/Apache-2.0.txt`. |
| SLF4J API | 1.7.36 | MIT | Transitive HikariCP dependency under `org.slf4j`. See `licenses/MIT-SLF4J.txt`. |
| sqlite-jdbc | 3.46.1.3 | Apache-2.0 | `org.sqlite` classes and native SQLite binaries. See `licenses/Apache-2.0.txt` and `licenses/NOTICE-sqlite-jdbc.txt`. |
| Zentus SQLite JDBC portions | included by sqlite-jdbc 3.46.1.3 | BSD-2-Clause | See `licenses/BSD-2-Clause-Zentus.txt`. |
| SQLite | 3.46.1 | Public Domain | Native SQLite code included by sqlite-jdbc; see the upstream SQLite copyright page and sqlite-jdbc release documentation. |
| pgJDBC | 42.7.5 | BSD-2-Clause | `org.postgresql` classes. See `licenses/BSD-2-Clause-pgJDBC.txt`. |
| OnGres SCRAM | 3.1, embedded by pgJDBC | BSD-2-Clause | See `licenses/BSD-2-Clause-OnGres-SCRAM-2017.txt`. |
| OnGres StringPrep/SASLprep | 2.2, embedded by pgJDBC | BSD-2-Clause | See `licenses/BSD-2-Clause-OnGres-StringPrep-2019.txt`. |
| Checker Qual | 3.48.3 | MIT | Transitive pgJDBC dependency under `org.checkerframework`. See `licenses/MIT-Checker-Qual.txt`. |
| MariaDB Connector/J | 3.5.2 | LGPL-2.1-or-later | `org.mariadb` classes. See `licenses/LGPL-2.1-or-later-MariaDB-Connector-J.txt` and the section 6 note above. |

Gson and Paper/Bukkit API classes are not included in the inspected Stats 0.3.1 JAR.

## Repository and build-only components

| Component | Version | Scope | License |
| --- | --- | --- | --- |
| Gradle wrapper | 9.6.1 | Wrapper scripts and `gradle-wrapper.jar` are committed; not included in Stats JAR | Apache-2.0 |
| Shadow Gradle plugin | 9.6.1 | Build tool only | Apache-2.0 |
| Paper API | 26.2 build 111 | `compileOnly` and test compile dependency; not included in Stats JAR | See the Paper repository's composite `LICENSE.md` |
| JUnit | 5.11.4 | Test only; not included in Stats JAR | EPL-2.0 |

The project has no dependency lockfile. Exact direct versions are declared in `build.gradle.kts`; Gradle resolves the transitive versions shown above.

## Official sources

- HikariCP 5.1.0: https://github.com/brettwooldridge/HikariCP/tree/HikariCP-5.1.0
- SLF4J 1.7.36: https://github.com/qos-ch/slf4j/tree/v_1.7.36
- sqlite-jdbc 3.46.1.3: https://github.com/xerial/sqlite-jdbc/tree/3.46.1.3
- SQLite copyright and public-domain statement: https://www.sqlite.org/copyright.html
- pgJDBC 42.7.5: https://github.com/pgjdbc/pgjdbc/tree/REL42.7.5
- Checker Framework 3.48.3: https://github.com/typetools/checker-framework/tree/checker-framework-3.48.3
- MariaDB Connector/J 3.5.2: https://github.com/mariadb-corporation/mariadb-connector-j/tree/3.5.2
- GNU LGPL 2.1: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
- Gradle: https://github.com/gradle/gradle
- Shadow: https://github.com/GradleUp/shadow
- Paper: https://github.com/PaperMC/Paper
- JUnit: https://github.com/junit-team/junit-framework
