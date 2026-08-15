# Stats

**Stats by PLAYCITY BLOCK** — 릴리스 `0.3.2`

[English](README.md) · [저장소](https://github.com/tdbgo/stats-minecraft-plugin) · [이슈](https://github.com/tdbgo/stats-minecraft-plugin/issues) · [릴리스 v0.3.2](https://github.com/tdbgo/stats-minecraft-plugin/releases/tag/v0.3.2)

Stats는 플레이어 활동을 조회 가능한 형태로 기록하는 서버 전용 Paper 플러그인입니다. 대시보드도, 관리 도구도, 원시 이벤트 로그도 아닙니다. 문서화된 고정 범위의 활동 카운터만 수집해 운영자가 관리하는 데이터베이스에 저장하므로, 보고서는 일반 SQL로 직접 만들 수 있습니다.

설계 목표는 켜 두고 신경 쓰지 않아도 되는 수집기입니다. 이벤트는 메모리에서 집계합니다. 전용 I/O 스레드 하나가 누적된 배치를 일정 주기로 — 기본 300초 — 데이터베이스에 기록하며, 이벤트마다 쓰지 않습니다. 기록할 내용이 없으면 데이터베이스 연결 자체를 요청하지 않습니다.

데이터 양은 의도적으로 제한합니다. 명령어는 정규화된 canonical 키와 짧은 허용 목록 variant로 축약하고, 블록 재료는 약 20여 개 그룹으로 합치며, 플레이어별 행은 시간 또는 일 단위로 키를 잡습니다. 같은 플레이어·날짜·재료 그룹·행동의 반복 블록 이벤트는 이벤트마다 행을 만들지 않고 한 행으로 합치며, 좌표는 남기지 않습니다.

전송은 데이터베이스 장애를 견디도록 설계했습니다. 비어 있지 않은 배치마다 UUID를 부여하고, 전송 전에 체크섬이 붙은 로컬 spool 파일로 기록하며, 사실 행과 같은 트랜잭션 안에서 `ingest_batch` 원장에 등록합니다. 실패한 플러시는 같은 batch id로 다음 플러시 또는 다음 서버 기동에서 재시도하며 중복 합산되지 않습니다.

---

## 1. 요구 사항

| 항목 | 요구 사항 |
| --- | --- |
| 서버 | Paper, Minecraft/Paper `26.2` |
| 검증된 빌드 | Paper 26.2 build 112 (컴파일 및 테스트 대상) |
| Java | 25 |
| 설치 위치 | 서버 전용 — 클라이언트에는 아무것도 설치하지 않습니다 |
| 저장소 | SQLite(내장), PostgreSQL, MySQL/MariaDB |

플러그인 descriptor는 `api-version: "26.2"`를 선언하며, 프로젝트는 Paper API `26.2.build.112-stable`로 빌드합니다. 26.2 계열의 다른 빌드는 이 저장소에서 검증하지 않았습니다.

MySQL과 MariaDB는 MariaDB Connector/J로 연결하며, 이 드라이버는 Paper가 별도로 확보합니다. [네트워크 동작](#63-네트워크-동작)을 참고하십시오.

## 2. 설치

1. 서버를 정지합니다.
2. `Stats-0.3.2.jar`를 Paper의 `plugins` 디렉터리에 복사합니다.
3. 서버를 한 번 시작합니다. Stats는 `plugins/Stats/config.yml`과 `plugins/Stats/command-aliases.yml`을 생성한 뒤 거기서 멈춥니다. `setup.enabled`가 `false`이므로 수집도, 데이터베이스 접속도 하지 않습니다.
4. `plugins/Stats/config.yml`을 편집합니다.
5. `setup.enabled: true`로 변경한 뒤 서버를 재시작하거나 `/stats reload`를 실행합니다.

3단계는 의도된 동작입니다. 첫 기동에서 `Stats is in safe setup mode (setup.enabled=false).` 로그가 남습니다. 이 경고는 설치가 정상이라는 뜻입니다.

첫 기동에서는 Paper가 선언된 데이터베이스 라이브러리를 확보하기 위해 Maven Central 접근이 필요할 수 있습니다. 이는 MySQL뿐 아니라 모든 백엔드에 해당합니다. [네트워크 동작](#63-네트워크-동작)을 참고하십시오.

## 3. 빠른 시작 (약 5분)

내장 SQLite 백엔드를 사용하는 경로입니다. 데이터베이스 서버도, 계정도, 데이터베이스 네트워크 접근도 필요 없습니다.

1. JAR를 설치하고 위 절차대로 서버를 한 번 시작합니다.
2. `plugins/Stats/config.yml`을 열어 한 줄만 바꿉니다.

   ```yaml
   setup:
     enabled: true
   ```

   `database.type: sqlite`는 그대로 둡니다.
3. 콘솔 또는 OP 권한으로 `/stats reload`를 실행합니다. 콘솔에 `Stats enabled. DB=sqlite prefix=mstats_`가 기록됩니다.
4. 확인합니다.

   ```text
   /stats status
   /stats db ping
   ```

   `status`는 `active=true`와 데이터베이스 종류를 표시해야 합니다. `db ping`은 밀리초 지연시간과 함께 `OK` 줄을 반환해야 합니다.
5. 1분 정도 이동하고, 블록을 부수고, 명령을 실행한 뒤 강제로 기록합니다.

   ```text
   /stats flush
   ```

   `Stats: flush done (rows=N)`이 표시됩니다.
6. 데이터베이스 파일은 `plugins/Stats/stats.db`입니다. 아무 SQLite 클라이언트로 조회할 수 있습니다.

   ```sql
   SELECT last_known_name, first_seen_at, last_seen_at FROM mstats_dim_player;
   SELECT day, playtime_sec, sessions, deaths FROM mstats_fact_player_day;
   ```

나중에 PostgreSQL이나 MySQL/MariaDB로 옮기려면 `database.type`과 접속 설정을 바꾸고 reload 하십시오. 종류 변경은 기존 행을 이관하지 않습니다. [업그레이드와 호환성](#7-업그레이드와-호환성)을 참고하십시오.

## 4. 기본 사용

`setup.enabled`가 `true`가 되면 Stats는 추가 조작 없이 동작합니다.

**서버 실행 중 일어나는 일**

- `tick.intervalSeconds`(기본 5초)마다 반복 작업이 접속 중인 플레이어의 플레이타임, AFK 시간, 표본 이동거리를 계산합니다.
- Paper 이벤트가 채팅, 명령, 블록 설치/파괴, 사망, 처치, 텔레포트, 접속, 종료, 월드 변경 카운터를 더합니다.
- 마지막 활동 신호로부터 `afk.thresholdSeconds`(기본 60초)가 지나면 AFK로 판정합니다. 블록 경계를 넘는 이동, 채팅, 명령, 블록 설치/파괴, 텔레포트가 활동 신호입니다. 시야 회전만으로는 활동으로 보지 않습니다.
- `flush.intervalSeconds`(기본 300초)마다 누적 카운터를 비우고, spool에 기록한 뒤, 하나의 트랜잭션으로 데이터베이스에 씁니다.
- 버퍼가 비어 있으면 데이터베이스 연결을 열지 않고 플러시를 끝냅니다.

**데이터가 저장되는 위치**

설정한 테이블 prefix(기본 `mstats_`) 아래에 기록합니다.

| 테이블 | 단위 |
| --- | --- |
| `mstats_dim_player` | 플레이어당 1행 |
| `mstats_fact_session` | 종료된 세션당 1행 |
| `mstats_fact_player_hour` | 플레이어 × 시간 |
| `mstats_fact_player_day` | 플레이어 × 일 |
| `mstats_fact_command_hour` / `_day` | 플레이어 × 버킷 × 명령 variant |
| `mstats_fact_block_group_day` | 플레이어 × 일 × 재료 그룹 × 설치/파괴 |
| `mstats_fact_death_day` | 플레이어 × 일 × damage cause |
| `mstats_dim_command`, `mstats_dim_command_variant` | 명령 차원 |
| `mstats_meta`, `mstats_ingest_batch` | 스키마 메타데이터와 멱등성 원장 |

시간 버킷은 UTC 정시, 일 버킷은 UTC 날짜입니다. 전체 컬럼 목록은 [docs/SCHEMA.md](docs/SCHEMA.md), 정확한 지표 정의는 [docs/DATA_CATALOG.md](docs/DATA_CATALOG.md)에 있습니다.

Stats는 게임 내 리포트, 랭킹, 웹 UI를 제공하지 않습니다. 보고서는 조회 계층의 역할이며, 저장된 스키마로 만들 수 있는 지표는 [docs/ANALYTICS_IDEAS.md](docs/ANALYTICS_IDEAS.md)에 정리되어 있습니다.

## 5. 명령어와 권한

명령은 `/stats` 하나, 권한은 `stats.admin` 하나입니다.

| 권한 | 기본값 | 범위 |
| --- | --- | --- |
| `stats.admin` | OP (`default: op`) | 모든 `/stats` 하위 명령 |

`stats.admin` 권한 **또는** OP 상태이면 통과합니다. 콘솔은 항상 통과합니다.

| 명령 | 동작 | 설정 게이트 |
| --- | --- | --- |
| `/stats` 또는 `/stats help` | 하위 명령 목록을 출력합니다. | `commands.enabled` |
| `/stats reload` | `config.yml`과 `command-aliases.yml`을 다시 읽고 I/O 스레드에서 새 런타임을 구성한 뒤, 완전히 준비된 다음에만 교체합니다. 실패하면 기존 런타임을 계속 사용합니다. | `commands.allowReload` |
| `/stats status` | 버전, `active`/`initializing`, 데이터베이스 종류와 테이블 prefix, 보류 행 수, durable pending batch 수, retired runtime 수, 최근 reload·flush 시도·flush 성공·flush 오류를 출력합니다. | `commands.enabled` |
| `/stats db ping` | I/O 스레드에서 `SELECT 1`을 실행하고 왕복 밀리초를 보고합니다. | `commands.allowDbPing` |
| `/stats db health` | 커넥션 풀 카운터(active, idle, total, awaiting)를 출력합니다. SQL은 실행하지 않습니다. | `commands.enabled` |
| `/stats flush` | 즉시 비동기 플러시를 예약합니다. 버퍼가 비어 있으면 `nothing to flush`를 보고하고, 중복 예약은 거부합니다. | `commands.allowForceFlush` |

`db ping`, `db health`, `flush`는 활성 런타임이 필요합니다. 안전 설정 모드에서는 `Stats: inactive.`로 응답합니다.

import, export, 조회, 마이그레이션 하위 명령은 없습니다.

## 6. 설정, 저장소, 네트워크 동작

실제 파일은 `plugins/Stats/config.yml`이고 현재 `config-version`은 `4`입니다. 모든 키와 기본값, 검증 및 런타임 동작은 [docs/CONFIG.md](docs/CONFIG.md)에 있습니다.

### 6.1 주요 설정

```yaml
config-version: 4

setup:
  enabled: false          # true가 되기 전에는 아무것도 동작하지 않습니다

database:
  type: sqlite            # sqlite | postgres | mysql
  tablePrefix: "mstats_"
  queryTimeoutSeconds: 30
  host: "127.0.0.1"
  port: 5432              # MySQL/MariaDB는 3306으로 변경
  database: "minecraft"
  schema: "public"        # PostgreSQL 전용
  username: "stats"
  password: "CHANGE_ME"
  pool:
    maximumPoolSize: 2
    minimumIdle: 0
  migrations:
    autoCreateTables: true
    autoMigrate: true

flush:
  intervalSeconds: 300
  maxBatchRows: 5000

tick:
  intervalSeconds: 5

afk:
  thresholdSeconds: 60
```

운영에서 특히 중요한 항목입니다.

- `database.type`은 `sqlite`(`sqlite3` 포함), `postgres`(`postgresql`, `pg` 포함), `mysql`(`mariadb`, `maria` 포함)을 허용합니다. 알 수 없는 값은 SQLite로 대체하지 않고 시작을 실패시킵니다.
- 배포되는 `port` 값은 `5432`입니다. MySQL과 MariaDB 운영자는 `3306`을 직접 지정해야 합니다. 파일에 존재하는 값이 엔진별 코드 기본값보다 항상 우선하므로, `5432`를 그대로 두면 MySQL 설정이 잘못된 포트를 향하게 됩니다.
- `database.tablePrefix`는 문자나 밑줄로 시작하고 영문자·숫자·밑줄만 포함하며 24자 이내여야 합니다. 끝의 `_`는 없으면 자동으로 붙습니다. 위반 시 활성화에 실패합니다.
- 원격 풀 기본값이 `maximumPoolSize: 2`, `minimumIdle: 0`인 이유는 플러시가 단일 스레드에서 직렬 처리되고 유휴 연결을 유지할 이유가 없기 때문입니다. SQLite는 설정값과 무관하게 연결 1개로 고정됩니다.
- 디스크의 `config.yml`을 편집해도 재시작이나 `/stats reload` 전에는 반영되지 않습니다.

### 6.2 저장소

- **SQLite** — `plugins/Stats/stats.db` 파일, WAL 저널, 네트워크 없음. 최초 도입이나 소형 서버에 적합합니다. 동시 쓰기 처리량과 초장기 보관이 알려진 한계입니다.
- **PostgreSQL** — 기준 대상입니다. `applicationName=Stats`와 설정한 `currentSchema`를 지정한 `PGSimpleDataSource`로 연결을 구성합니다.
- **MySQL / MariaDB** — MariaDB Connector/J를 사용해 `jdbc:mariadb://` URL로 연결합니다.

세 엔진 모두 같은 논리 스키마(스키마 버전 `2`)를 사용합니다. 정적 DDL은 `sql/sqlite/schema.sql`, `sql/postgres/schema.sql`, `sql/mysql/schema.sql`에 있습니다. 엔진 차이는 [docs/DB_COMPAT.md](docs/DB_COMPAT.md)에서 다룹니다.

전송되지 않은 배치는 `plugins/Stats/spool/<storage-id>/`에 보관합니다. `<storage-id>`는 데이터베이스 종류, 테이블 prefix, JDBC 대상을 해시한 값이며 비밀번호는 포함하지 않습니다. spool 파일에는 CRC32 체크섬이 있고 원자적으로 이동합니다. 손상된 파일은 삭제하지 않고 활성화를 실패시킵니다. [docs/FLUSHING.md](docs/FLUSHING.md)를 참고하십시오.

### 6.3 네트워크 동작

Stats 자체에는 텔레메트리, 업데이트 확인, 메트릭 보고, 어떤 종류의 HTTP 엔드포인트도 없습니다. 여는 연결은 설정한 데이터베이스로의 JDBC 연결뿐입니다. `database.type: sqlite`에서는 네트워크 연결을 전혀 열지 않습니다.

별개의 경우가 하나 있으며, 이는 플러그인이 아니라 Paper의 동작입니다. `plugin.yml`은 `org.mariadb.jdbc:mariadb-java-client:3.5.2`를 Paper 라이브러리로 선언합니다. MariaDB Connector/J는 `Stats-0.3.2.jar`에 shade **되지 않으며**, 포함되면 빌드가 실패합니다. Paper가 이 좌표를 Maven Central에서 확보해 자체 라이브러리 저장소에 캐시합니다.

구체적인 의미는 다음과 같습니다.

- 이 선언은 무조건적입니다. `database.type`과 무관하므로 SQLite나 PostgreSQL 서버도 같은 확보 절차를 거칩니다.
- Paper 라이브러리 캐시에 해당 아티팩트가 이미 있으면 내려받지 않고 기동합니다.
- 없으면 그 기동에서 Maven Central에 접근할 수 있어야 합니다.
- 한 번 캐시되면 이후 기동에서 다시 내려받지 않습니다.

해당 아티팩트가 Paper 라이브러리 캐시에 이미 있는 경우가 아니라면, 완전 오프라인 최초 설치는 지원 대상으로 간주하지 마십시오.

## 7. 업그레이드와 호환성

**0.3.2로 업그레이드**

1. 서버를 정상 종료합니다. 핫 리로드 도구는 사용하지 마십시오.
2. 데이터베이스를 백업합니다.
3. 기존 JAR를 `Stats-0.3.2.jar`로 교체합니다.
4. 서버를 시작하고 스키마 마이그레이션과 durable batch 복구 로그를 확인합니다.
5. `/stats status`와 `/stats db ping`을 확인합니다.
6. 약간의 활동을 만든 뒤 `/stats flush`를 실행하고 fact 테이블이 증가했는지 확인합니다.

**0.3.2의 호환성**

- `config-version`은 `4`, 스키마 버전은 `2`로 유지됩니다.
- 테이블명, 컬럼, 명령, 권한, 수집 동작은 0.3.1과 동일합니다.
- 기존 SQLite, PostgreSQL, MySQL, MariaDB 설정은 그대로 유효합니다.
- 운영상 유일한 변경은 MariaDB Connector/J가 번들 대신 Paper 라이브러리로 제공된다는 점입니다.

**스키마와 설정 마이그레이션**

- `autoCreateTables: true`이면 활성화 시 누락된 테이블과 인덱스를 생성합니다.
- `autoMigrate: true`이면 스키마 v1 데이터베이스에 텔레포트 열 4개를 `ALTER TABLE`로 추가합니다. `false`이면 누락 열이 있을 때 활성화를 실패시킵니다.
- 기록된 `schema_version`이 플러그인 지원 범위보다 높으면 활성화를 실패시킵니다.
- 설정 파일은 자동으로 다시 작성하지 **않습니다**. 누락된 키는 메모리상 기본값으로만 대체되며 디스크 파일은 그대로 둡니다. config 버전 1~4를 읽을 수 있습니다. [docs/CONFIG_MIGRATION.md](docs/CONFIG_MIGRATION.md)를 참고하십시오.
- `plugins/Stats/command-aliases.yml`은 최초 1회만 생성되며 업그레이드가 덮어쓰지 않습니다. 이후 릴리스의 새 canonical 규칙은 직접 병합해야 합니다.

**데이터베이스 백엔드 변경**

`/stats reload`로 다른 데이터베이스를 지정할 수는 있지만 데이터는 이동하지 않습니다. 은퇴한 런타임이 남은 배치를 이전 대상에 마저 기록한 뒤 종료합니다. 엔진 간 이력 복사는 외부 도구가 필요하며, [docs/DATA_MIGRATION.md](docs/DATA_MIGRATION.md)는 그 작업에 대한 설계 제안일 뿐 구현된 기능 설명이 아닙니다.

## 8. 한계와 의도적 비수집

### 8.1 저장하는 데이터

- **플레이어 식별** — UUID, 최초/최종 관측 시각, 편의용 `last_known_name`.
- **세션** — 접속·종료 시각, 지속시간, AFK 초, 시작 월드, 종료 월드.
- **플레이타임과 AFK** — 플레이어×시간 단위 초. 플레이타임은 플레이어×일로도 롤업합니다.
- **활동 분(active minutes)** — 분 단위 bitset에서 도출한 시간당 0~60 값.
- **채팅 volume** — 플레이어×시간 단위 메시지 수와 총 문자 수.
- **명령** — canonical 명령 키와 variant별 실행 수, 시간 및 일 단위.
- **블록** — 플레이어×시간 단위 설치/파괴 총량, 그리고 일 단위 재료 그룹×동작별 수.
- **사망과 처치** — 일 단위 damage cause별 사망 수, PvP/몹 처치 수.
- **이동** — 플레이어×시간 단위 정수 미터 표본 거리.
- **텔레포트** — 시간 및 일 단위 횟수와 같은 월드 내 거리.

### 8.2 저장하지 않는 데이터

- **좌표.** 위치는 거리 계산을 위해 메모리에서만 읽고 즉시 폐기합니다. X/Y/Z, 경로 이력, 이벤트별 위치를 남기지 않습니다.
- **채팅 본문.** 메시지 본문은 spool에도 데이터베이스에도 기록하지 않습니다. 위에 설명한 시간당 메시지 수와 문자 수만 남습니다.
- **원시 명령줄과 인자.** 정규화 후에는 canonical 명령 키와 허용 목록 variant만 남습니다. 대상 플레이어명, 메시지 본문, 좌표 인자는 폐기합니다.
- **IP 주소.** 읽지도, 해시하지도, 저장하지도 않습니다. `mstats_fact_session.ip_hash`는 스키마 호환을 위해 존재하며 이 수집기는 값을 쓰지 않습니다.
- **클라이언트 brand와 locale.** 마찬가지로 `client_brand`, `locale` 열은 존재하지만 값을 쓰지 않습니다.
- **인벤토리 스냅샷**, 아이템 획득·버리기·제작 이벤트.
- **개별 블록 이벤트.** 이벤트별 시각과 위치는 물론 원본 `Material` 이름도 남기지 않고, 재료 그룹별 일 집계만 저장합니다.

### 8.3 미구현

- 월드별 체류 시간과 dimension 키.
- 가한/받은 피해량.
- TPS, MSPT 같은 서버 상태 샘플링.
- 위험 감지, 경보, 자동 제재. [docs/RISK_DETECTION.md](docs/RISK_DETECTION.md)는 외부 분석 계층에 대한 설계 제안입니다.
- 옵트인 방식의 채팅/IP 수집.
- 원시 이벤트 스트림 또는 이벤트별 WAL.
- 보관 정책, 정리, 파티셔닝. `mstats_ingest_batch`는 비어 있지 않은 플러시당 최대 1행씩 늘어나며 자동으로 정리되지 않습니다.

### 8.4 정확도 한계

- 이동거리는 `tick.intervalSeconds`마다 취한 위치 표본 사이 직선거리의 합입니다. 곡선이나 왕복 이동은 실제보다 짧게 측정됩니다. 주기를 낮추면 정확도는 오르고 tick 비용도 오릅니다.
- 단일 이동 표본은 0보다 크고 1000미터 **미만**일 때만 반영합니다. 정확히 1000미터인 표본은 제외됩니다. 월드 간 이동은 0으로 처리합니다. 텔레포트는 별도로 집계하며 월드 간 텔레포트의 거리는 0입니다.
- `playtime_sec`는 전체 접속 시간이고 `afk_sec`는 그 *안*의 AFK 구간입니다. 두 값은 겹치므로 서로 더하면 안 됩니다.
- `/stats reload`는 열린 세션을 닫고 새로 시작하므로, reload 한 번이 한 번의 접속을 두 개의 `fact_session` 행으로 나눌 수 있습니다.
- 아직 비워지지 않은 메모리 카운터는 플러시 사이에 프로세스가 강제 종료되면 유실될 수 있습니다. 정상 종료는 세션을 닫고 최종 플러시를 시도하며, 데이터베이스에 접근할 수 없으면 해당 배치는 다음 기동을 위해 spool에 남습니다.

## 9. 문제 해결

| 증상 | 원인과 조치 |
| --- | --- |
| 로그에 `Stats is in safe setup mode (setup.enabled=false).` | 설정 전 정상 상태입니다. `setup.enabled: true`로 바꾼 뒤 재시작하거나 `/stats reload`를 실행합니다. |
| `/stats`가 `Stats: no permission.` 응답 | 발신자에게 `stats.admin` 권한도 OP 상태도 없습니다. |
| `/stats`가 `Stats: commands are disabled in config.` 응답 | `commands.enabled`가 `false`입니다. `/stats reload`도 함께 막히므로 디스크의 `config.yml`을 편집하고 재시작해야 합니다. |
| `/stats status`의 `active=false` | 안전 설정 모드이거나 마지막 초기화가 실패한 상태입니다. `lastFlushError`와 서버 로그를 확인합니다. |
| reload가 `previous runtime kept` 보고 | 새 설정 초기화가 실패했습니다. 지원되지 않는 `database.type`, 잘못된 `tablePrefix`, 접근 불가 호스트, 잘못된 자격증명, `command-aliases.yml` 문법 오류 등입니다. 기존 런타임은 계속 수집합니다. 파일을 고치고 다시 reload 하십시오. |
| `Unsupported config-version` | 파일이 1 미만 또는 4 초과 버전을 선언했습니다. |
| `Schema migration required: missing ...` 로 활성화 실패 | 구 스키마에 대해 `autoMigrate`가 `false`입니다. DDL을 직접 적용하거나 `autoMigrate: true`로 바꿉니다. |
| `Database schema version N is newer than supported version 2` 로 활성화 실패 | 더 새로운 Stats가 쓴 데이터베이스입니다. 데이터베이스를 되돌리지 말고 플러그인을 업그레이드하십시오. |
| 기동 시 `org.mariadb.jdbc.Driver`를 찾지 못함 | Paper가 선언된 라이브러리를 확보하지 못했습니다. 한 번의 기동 동안 Maven Central 접근을 허용하거나, Paper 라이브러리 캐시에 해당 아티팩트를 미리 넣으십시오. |
| `/stats flush`가 `flush failed; batch retained` 보고 | 배치는 메모리와 spool에 남아 있습니다. 데이터베이스를 복구한 뒤 `/stats flush`를 다시 실행하거나 재시작하십시오. 복구는 같은 `batch_id`로 재생하므로 중복 합산되지 않습니다. |
| `/stats status`의 `durablePendingBatches` 증가 | 쓰기가 반복 실패하고 있습니다. `lastFlushError`, 연결성, 자격증명, 디스크 여유를 확인합니다. |
| spool 체크섬/형식 오류로 활성화 실패 | spool 파일이 손상되었습니다. 의도적으로 보존됩니다. 조작하기 전에 `plugins/Stats/spool/`과 로그를 복사해 두십시오. |
| 호스트는 살아 있는데 `/stats db ping` 실패 | `queryTimeoutSeconds`, `pool.connectionTimeoutMs`, TLS 설정, 그리고 포트가 엔진과 맞는지 확인합니다. PostgreSQL은 `5432`, MySQL/MariaDB는 `3306`입니다. |
| 바쁜 서버인데 수치가 낮음 | 플러시가 끝나기 전에는 아무것도 기록되지 않습니다. 주기를 기다리거나 `/stats flush`를 실행하십시오. |

자세한 운영 절차는 [docs/OPERATIONS.md](docs/OPERATIONS.md)에 있습니다.

## 10. 지원과 라이선스

- 소스: <https://github.com/tdbgo/stats-minecraft-plugin>
- 이슈: <https://github.com/tdbgo/stats-minecraft-plugin/issues>
- 릴리스 0.3.2: <https://github.com/tdbgo/stats-minecraft-plugin/releases/tag/v0.3.2>

문제를 보고할 때는 Stats 버전, Paper 빌드, Java 버전, `database.type`, `/stats status` 출력을 포함하십시오. `config.yml`을 붙여 넣을 때는 반드시 비밀번호를 제거하십시오.

### 문서 안내

**현재 동작**

| 문서 | 내용 |
| --- | --- |
| [docs/CONFIG.md](docs/CONFIG.md) | 모든 설정 키, 기본값, 검증 및 동작 |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | 일상 운영, 장애 처리, 업그레이드 체크리스트 |
| [docs/DATA_CATALOG.md](docs/DATA_CATALOG.md) | 무엇을 수집하고 무엇을 수집하지 않는지 |
| [docs/SCHEMA.md](docs/SCHEMA.md) | 스키마 v2 테이블·컬럼 레퍼런스 |
| [docs/FLUSHING.md](docs/FLUSHING.md) | 집계, 배치, 내구성, 멱등성 |
| [docs/COMMAND_NORMALIZATION.md](docs/COMMAND_NORMALIZATION.md) | 명령 키와 variant 도출 방식 |
| [docs/DB_COMPAT.md](docs/DB_COMPAT.md) | 엔진 차이와 이식성 규칙 |
| [docs/CONFIG_MIGRATION.md](docs/CONFIG_MIGRATION.md) | config 버전 이력과 업그레이드 동작 |
| [docs/STORAGE_ESTIMATE.md](docs/STORAGE_ESTIMATE.md) | 근사 용량 산정 모델 |
| [CHANGELOG.md](CHANGELOG.md) | 0.3.0 – 0.3.2 릴리스 색인 |
| [docs/MODRINTH.md](docs/MODRINTH.md) | 프로젝트 등재 준비 참고 문서 |

**설계 제안 — 구현된 기능이 아닙니다.** 아래 문서는 플러그인에 존재하지 않는 워크플로와 도구를 설명합니다. 계획으로 읽으십시오:
[docs/DATA_MIGRATION.md](docs/DATA_MIGRATION.md),
[docs/IMPORT_EXISTING_DATA.md](docs/IMPORT_EXISTING_DATA.md),
[docs/PG_BACKFILL.md](docs/PG_BACKFILL.md),
[docs/ANALYTICS_IDEAS.md](docs/ANALYTICS_IDEAS.md),
[docs/RISK_DETECTION.md](docs/RISK_DETECTION.md).

### 소스에서 빌드

```text
./gradlew clean test build
```

빌드는 Gradle 9.6.1 wrapper와 Java 25 toolchain을 사용합니다. 결과물은 `build/libs/Stats-0.3.2.jar`입니다. `check` 태스크는 `verifyDistribution`도 실행해 플러그인 클래스 존재, MariaDB Connector/J와 Paper/Bukkit API 부재, 필수 라이선스 고지 포함을 검증합니다.

### 라이선스

프로젝트가 직접 작성한 소스, 테스트, 문서, 리소스는 [MIT License](LICENSE)로 제공되며 법적 저작권자는 `PLAYCITY`입니다.

제3자 구성요소에는 각자의 라이선스가 적용됩니다. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)와 [licenses](licenses) 디렉터리를 참고하십시오. HikariCP, SQLite JDBC, PostgreSQL JDBC 드라이버는 고지와 함께 번들됩니다. MariaDB Connector/J(LGPL-2.1-or-later)는 Stats JAR에 포함되지 않으며 Paper가 별도로 확보합니다.
