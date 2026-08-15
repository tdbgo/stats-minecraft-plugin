# Stats — 기존 데이터(Postgres) 백필 가이드

이 문서는 기존 서버에 이미 존재하는 플러그인 데이터(Plan/FAWE)를 **Stats Postgres 스키마(`mstats_*`)로 변환**해 적재하는 방법을 정리합니다.

## 1) 무엇이 백필되는가
- Plan(`plugins/Plan/database.db`)
  - `mstats_dim_player`
  - `mstats_fact_session` (월드는 “세션 내 체류시간이 가장 큰 월드”로 추정)
  - `mstats_fact_player_day` (세션을 day로 분할 롤업)
- FAWE(`plugins/FastAsyncWorldEdit/history/**/summary.db`)
  - `mstats_dim_command`, `mstats_dim_command_variant`
  - `mstats_fact_command_day`, `mstats_fact_command_hour` (WorldEdit 계열 중심)

## 2) 준비
- Postgres에 Stats 스키마 생성: `sql/postgres/schema.sql`
- Stats의 테이블 prefix 확인: `plugins/Stats/config.yml`의 `database.tablePrefix` (기본 `mstats_`)

## 3) 변환 파일 생성(워크스페이스에서 실행)
- 실행:
  - `python scripts/export_existing_to_pg.py --out out/pg-migration`
  - 또는 `powershell -File scripts/export_existing_to_pg.ps1`
- 출력:
  - `out/pg-migration/load.sql`
  - `out/pg-migration/*.csv`
  - `out/pg-migration/report.txt`

## 4) Postgres에 적재
`out/pg-migration` 디렉토리에서 `psql`을 실행하는 것이 가장 단순합니다(상대 경로 `\copy` 사용).

- 예시:
  - `cd out/pg-migration`
  - `psql "postgresql://USER:PASSWORD@HOST:5432/DBNAME" -f load.sql`

## 5) 주의사항
- `load.sql`은 “merge(합산)” 업서트를 사용합니다(`fact_player_day`, `fact_command_*`). 동일 데이터를 여러 번 적재하면 값이 누적될 수 있습니다.
- `fact_session`은 현재 “best-effort insert”이며, 타겟 DB가 비어있다는 가정이 가장 안전합니다.

