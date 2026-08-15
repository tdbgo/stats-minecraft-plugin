# Stats — DB 이관(Export/Import) 설계

목표: 기본 SQLite에서 시작한 서버가, 운영 중에 Postgres/MySQL로 **안전하게 데이터 이관**할 수 있게 한다.

핵심 제약:
- `dim_command`/`dim_command_variant`는 DB마다 ID가 달라질 수 있으므로, export는 `command_id/variant_id`가 아닌 **`command_key/variant_key` 기반**으로 해야 한다.
- DB 타입 전환은 `/stats reload`로 가능하지만, 자동 이관은 별도 절차가 필요하다.

## 1) 권장 운영 플로우(SQLite → Postgres 예시)
1. `/stats flush` (즉시 플러시)
2. (권장) 수집/플러시 잠시 중단(예: `/stats pause` 같은 운영 모드가 있으면 사용)
3. `/stats export <file>` 실행(소스 DB에서 파일 생성)
4. `config.yml`에서 `database.type: postgres` 및 접속 정보 설정
5. `/stats reload`로 대상 DB 연결/스키마 생성
6. `/stats import <file>` 실행(대상 DB로 적재)
7. `/stats status`로 정상 수집/플러시 재개 확인

## 1.1) tablePrefix 주의
- export 파일은 “논리 스키마”(예: `dim_player`, `fact_player_day`) 기준이므로 prefix와 무관합니다.
- import는 현재 설정된 `database.tablePrefix` 아래에 테이블을 생성/업서트합니다.
  - 기존 DB와 같은 prefix로 유지하면 혼동이 적습니다.
  - 다른 prefix로 옮길 수도 있지만, 같은 DB에 두 세트가 공존하게 됩니다.

## 2) 명령어(스펙 초안)
- `/stats export <path> [--days N] [--gzip] [--format jsonl]`
  - 기본: `jsonl`(NDJSON) + gzip 권장
  - `--days N`: 최근 N일만(용량/시간 절감)
  - export 중 비밀값은 로그에 출력 금지

- `/stats import <path> [--mode merge] [--dry-run]`
  - `merge`: 기존 데이터에 합산(upsert) 방식(기본 권장)
  - `dry-run`: 예상 row 수/용량/시간만 산출

- (옵션) `/stats migrate <target>`:
  - 내부적으로 export→reload→import를 가이드하는 “마법사” 형태
  - 실수 방지를 위해 2단계 확인(YES/토큰)

## 3) Export 포맷(엔진 독립)
### 파일 구성(권장)
- `stats-export-manifest.json`
  - `export_version`: 1
  - `plugin_version`, `schema_version`
  - `created_at`(UTC)
  - `range`: day 범위(옵션)
  - 파일 목록/해시(옵션)
- `players.jsonl`
- `sessions.jsonl`
- `player_hour.jsonl`, `player_day.jsonl`
- `command_hour.jsonl`, `command_day.jsonl`
- `block_group_day.jsonl`
- `death_day.jsonl`

### 레코드 공통 규칙
- 시간: UTC ISO-8601 문자열
- UUID: 문자열(예: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`)
- 커맨드: `command_key` + `variant_key`(없으면 빈 문자열)

### 예시(일부)
- `command_hour.jsonl`
  - `{ "player_uuid": "...", "hour_ts": "2025-01-01T10:00:00Z", "command_key": "worldedit:replace", "variant_key": "material=stone", "count": 3 }`
- `player_day.jsonl`
  - `{ "player_uuid": "...", "day": "2025-01-01", "playtime_sec": 3600, "sessions": 2, ... }`

## 4) Import 시 업서트 전략(권장)
- `dim_player`: UUID 기준 upsert
- `dim_command`: `command_key` 기준 upsert 후 `command_id` 조회
- `dim_command_variant`: `(command_id, variant_key)` 기준 upsert 후 `variant_id` 조회
- fact 테이블: PK 기준 upsert(merge 시에는 `count = count + excluded.count` 형태)

## 5) 일관성/오탐 방지
- export 시작 전 `flush` 강제 실행(소스 DB에 반영)
- export 중에는 수집/플러시를 “일시 정지”시키는 운영 모드가 있으면 가장 안전
- import는 비동기 배치로 실행하되, 진행률/속도/예상 남은 시간 출력

## 6) 주의(무제한 보관 서버)
- 장기 서버는 export 파일이 매우 커질 수 있으므로, 기본 export는 `--days N`을 권장
- full export가 필요하면 “월/분기 단위 스냅샷”으로 나눠 수행하는 운영 절차를 권장
