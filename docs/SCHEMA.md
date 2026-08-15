# Stats - 저장 스키마 v2

현재 스키마 버전은 `2`이며 SQLite, PostgreSQL, MySQL/MariaDB에서 같은 논리 구조를 사용합니다. 정적 DDL은 각각 `sql/sqlite/schema.sql`, `sql/postgres/schema.sql`, `sql/mysql/schema.sql`에 있습니다.

물리 테이블명에는 `database.tablePrefix`가 붙습니다. 아래 이름은 기본 prefix `mstats_` 기준입니다.

## 메타와 멱등성

- `mstats_meta`
  - PK: `key`
  - 현재 `schema_version`, `plugin_version` 저장
- `mstats_ingest_batch`
  - PK: `batch_id`(UUID/BINARY(16)/BLOB)
  - 컬럼: `created_at`
  - 목적: 동일 플러시 스냅샷 재시도 시 집계값 중복 증가 방지
  - 인덱스: `created_at`

`ingest_batch` 행과 사실 테이블 변경은 같은 트랜잭션에 포함됩니다.

## 차원

- `mstats_dim_player`
  - PK: `player_uuid`
  - `first_seen_at`, `last_seen_at`, `last_known_name`
- `mstats_dim_command`
  - PK: `command_id`, UK: `command_key`
  - `family`, `notes`
- `mstats_dim_command_variant`
  - PK: `variant_id`, FK: `command_id`
  - UK: `(command_id, variant_key)`
  - `variant_key` 예: `mode=creative`, `material=stone`, `target_kind=other`; 인자를 수집하지 않으면 빈 문자열

## 세션

- `mstats_fact_session`
  - PK: 자동 증가 `session_id`
  - `player_uuid`, `join_at`, `quit_at`, `duration_sec`, `afk_sec`, `join_world`, `quit_world`
  - 인덱스: `(player_uuid, join_at)`, `join_at`
  - `ip_hash`, `client_brand`, `locale` 열은 기존 호환을 위해 남아 있지만 현재 collector는 값을 쓰지 않습니다.

## 플레이어 시간 버킷

- `mstats_fact_player_hour`
  - PK: `(player_uuid, hour_ts)`
  - `playtime_sec`, `afk_sec`, `active_minutes`
  - `chat_messages`, `chat_chars`, `commands_total`
  - `blocks_placed_total`, `blocks_broken_total`
  - `distance_m`, `teleport_count`, `teleport_distance_m`
  - 인덱스: `hour_ts`
- `mstats_fact_player_day`
  - PK: `(player_uuid, day)`
  - `playtime_sec`, `sessions`, `deaths`, `kills_pvp`, `kills_mob`
  - `teleport_count`, `teleport_distance_m`
  - 인덱스: `day`

`active_minutes`는 동일 시간 내 누적 bitset의 개수이며 업서트 시 합산하지 않고 기존값과 새 누적값의 최댓값을 사용합니다.

## 명령, 블록, 사망

- `mstats_fact_command_hour`: PK `(player_uuid, hour_ts, variant_id)`, `count`
- `mstats_fact_command_day`: PK `(player_uuid, day, variant_id)`, `count`
- `mstats_fact_block_group_day`: PK `(player_uuid, day, group_key, action)`, `count`; action `0=place`, `1=break`
- `mstats_fact_death_day`: PK `(player_uuid, day, cause)`, `count`

원시 명령줄, 개별 블록 material, 좌표, 채팅 본문은 이 스키마에 없습니다.

## v1에서 v2

v2는 다음을 추가합니다.

- `mstats_ingest_batch`
- `mstats_fact_player_hour.teleport_count`
- `mstats_fact_player_hour.teleport_distance_m`
- `mstats_fact_player_day.teleport_count`
- `mstats_fact_player_day.teleport_distance_m`

`autoCreateTables: true`이면 ingest 테이블을 생성합니다. 기존 player hour/day 테이블에 열이 없으면 `autoMigrate: true`일 때 `ALTER TABLE ... ADD COLUMN`으로 추가하고, `false`이면 활성화를 실패시킵니다.

## 타입 매핑

- UUID: PostgreSQL `UUID`, MySQL/MariaDB `BINARY(16)`, SQLite `BLOB(16)`
- 시간: PostgreSQL `TIMESTAMPTZ`, MySQL/MariaDB `DATETIME(3)`, SQLite UTC ISO-8601 `TEXT`
- 일자: PostgreSQL/MySQL `DATE`, SQLite `YYYY-MM-DD` `TEXT`

장기 운영에서는 day 테이블을 영구 보관하고 hour 테이블은 조회 요구에 맞춰 파티셔닝 또는 보관기간을 별도로 정하는 방식을 권장합니다. 자동 보관/파티셔닝은 현재 플러그인 범위가 아닙니다.
