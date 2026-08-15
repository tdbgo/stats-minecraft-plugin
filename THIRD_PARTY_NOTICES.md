# Third-Party Notices

Stats project-authored source code and documentation are licensed under the MIT License in `LICENSE`. That license does not replace or relicense the third-party components below.

## Runtime and packaged components

| Component | Version | Purpose | Included in Stats JAR | License and preserved notice |
| --- | ---: | --- | --- | --- |
| HikariCP | 5.1.0 | JDBC connection pooling | Yes, shaded without relocation | Apache-2.0; `META-INF/licenses/Stats/Apache-2.0.txt` |
| SLF4J API | 1.7.36 | HikariCP runtime API | Yes, shaded without relocation | MIT; `META-INF/licenses/Stats/MIT-SLF4J.txt` |
| Xerial SQLite JDBC | 3.46.1.3 | Local SQLite driver and native libraries | Yes, shaded without relocation | Apache-2.0; upstream license entries plus `META-INF/licenses/Stats/Apache-2.0.txt` |
| Zentus SQLite JDBC portions | Included by SQLite JDBC 3.46.1.3 | Upstream-derived SQLite JDBC code | Yes, inside SQLite JDBC | BSD-2-Clause; `META-INF/licenses/Stats/BSD-2-Clause-Zentus.txt` |
| SQLite | Included by SQLite JDBC 3.46.1.3 | Native SQLite engine | Yes, inside SQLite JDBC | Public domain; see the SQLite copyright page below |
| PostgreSQL JDBC Driver | 42.7.5 | PostgreSQL driver | Yes, shaded without relocation | PostgreSQL/BSD-2-Clause; `META-INF/licenses/Stats/BSD-2-Clause-pgJDBC.txt` |
| OnGres SCRAM client/common | 3.1, embedded by pgJDBC | SCRAM authentication | Yes, inside pgJDBC | BSD-2-Clause; upstream entries plus the two OnGres license files under `META-INF/licenses/Stats` |
| OnGres StringPrep/SASLprep | 2.2, embedded by pgJDBC | SCRAM string preparation | Yes, inside pgJDBC | BSD-2-Clause; upstream entries plus the two OnGres license files under `META-INF/licenses/Stats` |
| Checker Framework qualifiers | 3.48.3 | pgJDBC runtime annotations | Yes, transitive and shaded without relocation | MIT; `META-INF/licenses/Stats/MIT-Checker-Qual.txt` |
| MariaDB Connector/J | 3.5.2 | MySQL and MariaDB driver | No; Paper resolves it from `plugin.yml` | LGPL-2.1-or-later; see the exact source and license below |

MariaDB Connector/J is excluded from the Shadow JAR. Paper downloads the unmodified Maven artifact and adds it to the plugin classpath as a separate JAR. This keeps the LGPL component outside the MIT-licensed Stats archive and independently replaceable. A fresh server needs access to Maven Central during its first Stats startup. Cached restarts do not require a new download.

## Compile, test, and build dependencies

| Component | Version | Scope | Included in Stats JAR | License |
| --- | ---: | --- | --- | --- |
| Paper API | 26.2 build 112 | Compile-only server API | No | Paper repository license and per-file notices |
| JUnit Jupiter | 5.11.4 | Test only | No | EPL-2.0 |
| Shadow Gradle plugin | 9.6.1 | Build only | No | Apache-2.0 |
| Gradle Wrapper / Gradle | 9.6.1 | Build bootstrap and build tool | No plugin runtime code | Apache-2.0 |

Paper API transitive dependencies are not copied into the Stats JAR. The build verifies that Paper, Bukkit, and MariaDB Connector/J classes are absent. It also verifies the unique notice and license paths listed above.

## Exact upstream sources

- HikariCP 5.1.0: <https://github.com/brettwooldridge/HikariCP/tree/HikariCP-5.1.0>
- SLF4J 1.7.36: <https://github.com/qos-ch/slf4j/tree/v_1.7.36>
- SQLite JDBC 3.46.1.3: <https://github.com/xerial/sqlite-jdbc/tree/3.46.1.3>
- SQLite copyright: <https://www.sqlite.org/copyright.html>
- PostgreSQL JDBC Driver 42.7.5: <https://github.com/pgjdbc/pgjdbc/tree/REL42.7.5>
- Checker Framework 3.48.3: <https://github.com/typetools/checker-framework/tree/checker-framework-3.48.3>
- MariaDB Connector/J 3.5.2: <https://github.com/mariadb-corporation/mariadb-connector-j/tree/3.5.2>
- MariaDB Connector/J license: <https://github.com/mariadb-corporation/mariadb-connector-j/blob/3.5.2/LICENSE>
- Paper: <https://github.com/PaperMC/Paper>
- Paper plugin library documentation: <https://docs.papermc.io/paper/dev/plugin-yml/#libraries>
- JUnit 5.11.4: <https://github.com/junit-team/junit5/tree/r5.11.4>
- Shadow 9.6.1: <https://github.com/GradleUp/shadow/tree/9.6.1>
- Gradle 9.6.1: <https://github.com/gradle/gradle/tree/v9.6.1>

Before publishing a binary, compare this inventory with Gradle's resolved runtime classpath and inspect the final JAR. The `verifyDistribution` task enforces the required notice entries and MariaDB exclusion.
