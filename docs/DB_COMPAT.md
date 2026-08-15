# Stats — DB 호환성(Postgres 중심 + MySQL/MariaDB 옵션)

이 프로젝트는 **Postgres를 기준**으로 스키마/쿼리를 설계하되, 운영 환경에 따라 **MySQL/MariaDB로도 동작**할 수 있도록 “가장 공통적인 SQL”로 설계합니다.

## 1) 기준 스키마
- 기본(즉시 동작): `sql/sqlite/schema.sql`
- Canonical DDL: `sql/postgres/schema.sql`
- 호환 DDL: `sql/mysql/schema.sql`
- 현재 논리 스키마 버전: `2` (`ingest_batch` 멱등성 ledger와 텔레포트 집계 열 포함)

## 2) 주요 차이와 대응
- SQLite(기본값)
  - 장점: 설치/계정/네트워크 없이 즉시 동작(초기 도입/테스트/소형 서버에 적합)
  - 주의: 동시 쓰기/대용량 장기 보관에서 한계가 있으므로, 운영 환경은 Postgres 권장
- UUID
  - Postgres: `UUID`
  - MySQL/MariaDB: `BINARY(16)` 권장(애플리케이션에서 변환), 또는 `CHAR(36)`
  - SQLite: `BLOB(16)` 또는 `TEXT`(UUID 문자열)
- Timestamp
  - Postgres: `timestamptz` 권장(UTC 저장)
  - MySQL/MariaDB: `TIMESTAMP(3)` 또는 `DATETIME(3)`(UTC로 통일)
  - SQLite: `TEXT`(UTC ISO-8601) 또는 `INTEGER`(epoch)
- Upsert
  - Postgres: `INSERT .. ON CONFLICT (...) DO UPDATE`
  - MySQL/MariaDB: `INSERT .. ON DUPLICATE KEY UPDATE`
  - SQLite: `INSERT .. ON CONFLICT(...) DO UPDATE` (버전 의존성이 있어, 최소 지원 버전을 명확히 권장)
- 플러시 멱등성
  - 세 엔진 모두 한 트랜잭션에서 `ingest_batch.batch_id`를 먼저 claim한 뒤 fact upsert를 실행
  - 이미 존재하는 `batch_id`는 성공 처리하되 집계를 다시 적용하지 않음
- 파티셔닝/장기 보관
  - 둘 다 가능하지만, Postgres가 운영/쿼리 측면에서 유연한 편이라 “무제한 보관 + 파티션”에 유리함

## 3) 구현 시 주의(이식성)
- 예약어/대소문자: 테이블/컬럼은 snake_case 소문자 고정
- 인덱스/제약: 복합 PK/UK 중심으로 설계(엔진별 기능에 의존하지 않기)
- 문자열 길이: Minecraft 이름 등은 적절한 상한을 두되, “플러그인/월드명”은 `TEXT`로 여유 있게
- 테이블 prefix: `database.tablePrefix`로 테이블 충돌을 방지(DDL은 기본 `mstats_` 기준)

## Paper 클래스 로더와 PostgreSQL

PostgreSQL 연결은 `driverClassName`/`DriverManager` 등록 조회에 의존하지 않고 플러그인 클래스 로더에서 직접 생성한 `PGSimpleDataSource`를 Hikari에 전달합니다. JDBC URL의 설정 의미와 durable spool 저장소 식별 문자열은 기존과 동일하게 유지합니다.

MariaDB Connector/J 3.5.2는 Stats 0.3.2부터 Shadow JAR에 포함하지 않습니다. Paper가 `plugin.yml`의 `libraries` 선언을 통해 Maven Central의 원본 JAR을 별도로 내려받아 플러그인 클래스 경로에 추가합니다. 기존 MySQL/MariaDB 설정, JDBC URL, 테이블, 데이터 의미는 변경하지 않습니다.
