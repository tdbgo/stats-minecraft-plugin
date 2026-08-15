# Stats — 기존 플러그인 데이터 통합/마이그레이션 가능성

요청 요지: “Stats를 지금부터 도입하더라도, 과거 데이터(이미 다른 플러그인이 수집한 것)를 Stats DB로 이관해서 복원할 수 있는가?”

결론: **가능한 범위가 있습니다.** 다만 Stats가 목표로 하는 “원본 데이터 수집(커맨드/블록/활동성)”을 과거까지 완벽히 복구하는 것은, 기존 플러그인이 무엇을 기록했는지에 따라 달라집니다.

## 1) 현재 서버에서 확인된 잠재 소스
- Plan(Player Analytics): `plugins/Plan/database.db` (SQLite)
- CoreProtect: `plugins/CoreProtect/database.db` (SQLite, 대용량)
- FAWE(FastAsyncWorldEdit) 히스토리: `plugins/FastAsyncWorldEdit/history/**/summary.db` (SQLite)
- WorldGuard UUID 캐시: `plugins/WorldGuard/cache/profiles.sqlite` (SQLite)
- (그 외) LuckPerms(H2), Essentials(YAML) 등

## 2) 소스별 “복원 가능한 것” / “불가능한 것”
### A) Plan(Player Analytics)
주요 테이블(예시):
- `plan_users`: uuid, name, registered
- `plan_sessions`: 세션 시작/종료, mob_kills, deaths, afk_time
- `plan_world_times`: 월드별 체류시간(게임모드별)
- `plan_tps`: TPS/플레이어수/CPU/RAM/엔티티 등 서버 상태 샘플
- `plan_ping`: 유저 ping 통계

Stats로 이관 가능:
- `mstats_dim_player`: uuid/이름/최초 관측 시각
- `mstats_fact_session`: join/quit/duration/afk(월드명은 Plan 단독으로는 제한적)
- `mstats_fact_player_day`: playtime/afk 기반 파생(세션 롤업)
- (선택) 서버 상태 스냅샷 테이블을 추가하면 TPS도 이관 가능

한계:
- Plan은 “블록 place/break”, “커맨드 사용” 같은 원본 이벤트를 기본적으로 제공하지 않음 → Stats의 핵심 원본 수집은 과거까지 복원 불가

### B) CoreProtect
주요 테이블(예시):
- `co_user`: uuid, user, time
- `co_block`: time/user/wid/x/y/z/type/action ... (블록 변경 로그)
- `co_container`: 컨테이너 인벤토리 변경 로그
- `co_command`: 커맨드 로그(서버 설정에 따라 수집 여부/형태가 다를 수 있음)
- `co_chat`: 채팅 로그(서버 설정에 따라)

Stats로 이관 가능(집계로 변환 전제):
- 블록 활동: `mstats_fact_player_day`, `mstats_fact_block_group_day`로 “일별 블록 파괴/설치(그룹별)”를 과거까지 롤업 가능
- 테러/그리프 탐지 신호: 컨테이너 파괴/변경 급증 등(좌표는 저장하지 않고 집계만 이관)
- (옵션) 커맨드/채팅이 기록되어 있다면 `mstats_fact_command_day/hour`, `chat_messages`도 일부 복원 가능

한계/주의:
- CoreProtect 원본에는 좌표/블록 세부 정보가 포함됨 → Stats로 이관할 때는 **집계만 저장**하고 민감/불필요 상세는 폐기하는 것이 권장
- DB가 커서(현재 수백 MB) full scan 이관은 시간이 걸릴 수 있음 → 최근 N일/월 단위 배치 이관 권장

### C) FAWE 히스토리(summary.db)
확인된 테이블:
- `_edits`: player(UUID 16B), time, bounds(x1..y2..z2), size, command

Stats로 이관 가능:
- WorldEdit/FAWE 커맨드 사용량: `command` 문자열을 `docs/COMMAND_NORMALIZATION.md` 규칙으로 정규화해 `mstats_fact_command_day/hour`로 이관
- (선택) “편집 규모(size)”를 저장하는 확장 테이블을 만들면, 대형 편집 감지/리포트도 가능

한계:
- 일반 커맨드/플레이 활동 전반은 포함하지 않음(WE 관련에 집중)

### D) WorldGuard uuid_cache
Stats로 이관 가능:
- uuid ↔ name 매핑을 `mstats_dim_player.last_known_name` 보정에 활용

## 3) “Stats로 완전 복원”이 어려운 항목(대표)
- `active_minutes` 같은 “분 단위 활동성”: 과거에 해당 분의 원본 이벤트가 없으면 정확 복원 불가(세션 기반 근사치는 가능)
- 커맨드 alias/정규화 기반의 전체 커맨드 통계: FAWE/WE 일부를 제외하면, 과거 로그가 없으면 복원 불가

## 4) 권장 이관 전략(현실적인 접근)
1) Plan으로 “세션/플레이타임/AFK” 백필(대시보드의 기본 KPI를 과거까지 복구)  
2) CoreProtect로 “블록 활동/그리프 신호”를 일 단위로 롤업 백필(용량/시간 절약)  
3) FAWE summary로 “WE 커맨드 사용/대형 편집” 백필  
4) 이후부터 Stats가 원본 수집을 시작(커맨드/블록/활동성의 정밀도는 Stats가 담당)

## 5) 다음 단계(원하시면 구현)
- “외부 소스 → Stats export 포맷(`docs/DATA_MIGRATION.md`)” 변환기를 스크립트(또는 `/stats import-plan` 같은 서브커맨드)로 구현
- CoreProtect/FAWE는 **최근 N일부터** 단계적으로 이관(서버 부하 관리)

