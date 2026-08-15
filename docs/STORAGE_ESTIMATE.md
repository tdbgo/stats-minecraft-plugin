# Stats — 예상 저장 용량(대략치)

전제: 이 플러그인은 “원시 이벤트 무제한 적재”가 아니라, `session` + `hour/day` 버킷 집계 중심(`docs/SCHEMA.md`)으로 저장한다.  
따라서 저장 용량은 대체로 **(활동 유저 수 × 활동 시간 × 커맨드 다양도)**에 비례한다.

## 0) 가장 큰 변수
- `fact_command_hour`/`fact_command_day`: 커맨드 사용 기록은 카디널리티가 커지기 쉬움(특히 플러그인 많을수록)
  - 권장: `dim_command`/`dim_command_variant`로 정규화해 INT 키 저장
  - args 캡처는 “화이트리스트/1개 인자”로 제한(이미 `docs/COMMAND_NORMALIZATION.md`에 반영)
- `fact_player_hour`: “활동한 hour만 생성”하면 매우 작아짐(24시간 전부 생성 금지)

## 1) 하루 증가량 공식(근사)
기호:
- `DAU`: 하루 활동 유저 수
- `H`: 유저당 하루 “활동 hour” 수(예: 평균 2시간이면 H=2)
- `Uch`: 유저당 “활동 hour 1개”에서 등장하는 (command_variant) 고유 개수

근사 행 수:
- `fact_player_hour` 행/일 ≈ `DAU * H`
- `fact_player_day` 행/일 ≈ `DAU`
- `fact_command_hour` 행/일 ≈ `DAU * H * Uch`
- `fact_command_day` 행/일 ≈ `DAU * Ucd` (Ucd=하루 고유 variant 개수, 대략 `H*Uch`보다 작을 수 있음)
- `ingest_batch` 행/일 ≤ `86400 / flush.intervalSeconds` (300초이면 최대 288행)

행 크기(인덱스 포함, 보수적 대략치):
- `fact_player_hour`: 200–400 bytes / row
- `fact_player_day`: 150–300 bytes / row
- `fact_command_hour`: 120–300 bytes / row (INT FK 기준)
  - `command_key`를 VARCHAR로 직접 저장하면 인덱스 때문에 이보다 **훨씬 커질 수 있음**
- `ingest_batch`: 50–150 bytes / row 수준의 작은 ledger로 가정하면 최대 약 5–16 MB/year(엔진/인덱스에 따라 차이)

## 2) 예시 시나리오(대략)
### A) 소형 서버
- `DAU=100`, `H=2`, `Uch=8`
- `fact_player_hour`: 100*2=200 rows/day → 약 40–80 KB/day
- `fact_command_hour`: 100*2*8=1,600 rows/day → 약 0.2–0.5 MB/day
- 합계(주요 테이블): 대략 **0.3–0.7 MB/day** → **9–21 MB/month** → **0.1–0.3 GB/year**

### B) 중형 서버
- `DAU=500`, `H=3`, `Uch=12`
- `fact_player_hour`: 1,500 rows/day → 약 0.3–0.6 MB/day
- `fact_command_hour`: 18,000 rows/day → 약 2–5 MB/day
- 합계: 대략 **3–7 MB/day** → **0.1–0.2 GB/month** → **1–3 GB/year**

### C) 대형 서버(커맨드 다양도 높음)
- `DAU=2,000`, `H=3`, `Uch=25`
- `fact_player_hour`: 6,000 rows/day → 약 1–2.5 MB/day
- `fact_command_hour`: 150,000 rows/day → 약 18–45 MB/day
- 합계: 대략 **20–50 MB/day** → **0.6–1.5 GB/month** → **7–18 GB/year**

## 3) “무제한 보관”을 현실적으로 유지하는 팁
- 반드시 파티셔닝: 월 단위 파티션(특히 `fact_command_hour`)
- day 롤업을 영구 저장(`fact_command_day`, `fact_player_day`)하고, hour는 “분석 목적에 꼭 필요한지” 재검토 권장
  - 무제한이 “데이터 삭제 없음”이라면, 최소한 “hour는 오래된 파티션을 압축/아카이브”하는 운영이 필요

## 4) 5분 배치 업로드 시 임시(로컬) 스풀 용량
플러시 주기가 길어질수록 “미전송 집계”가 메모리/로컬 큐에 쌓인다.
- 집계 기반이면 스풀은 “행 수”가 아니라 “카운터 상태” 위주라 작음
- 대략치: `DAU * Uch` 수준의 카운터(몇 천~수만)만 유지하면 보통 수 MB 이내로 설계 가능
- 현재 구현은 비어 있지 않은 집계 스냅샷을 DB 전송 전에 로컬 spool에 기록합니다. 정상 상태에서는 커밋 직후 삭제되므로 보통 한 플러시 분량 이하이고, DB 장애 중에는 재시도 대상 불변 snapshot이 유지됩니다.
- spool 파일은 최대 64 MiB로 검증하며 원시 이벤트를 담지 않습니다. 이 한도를 넘는 비정상 snapshot은 DB 전송 전에 실패해 메모리에 유지됩니다.

## 5) 원시 이벤트 대비 차이

- 블록 이벤트 원본은 파괴/설치 횟수만큼 행이 늘지만 현재 구조는 동일 `player/day/group/action`을 1행으로 합친다.
- 위치 원본을 1초마다 저장하면 플레이어 1명당 하루 86,400개 지점이 생길 수 있지만 현재는 좌표를 버리고 활동한 시간당 `distance_m` 정수 1개만 남긴다.
- 명령 원본도 실행 횟수만큼 행을 만들지 않고 동일 `player/hour/variant`를 합친다.

따라서 건축/이동이 많은 서버일수록 원시 로그와의 차이는 수십~수천 배까지 커질 수 있다. 실제 비율은 이벤트 빈도와 고유 명령 variant 수에 좌우된다.
