# Stats - 현재 수집 데이터 카탈로그

이 문서는 구현된 수집 범위를 기준으로 합니다. 기본 저장 단위는 session/hour/day 집계이며 원시 이벤트 스트림은 저장하지 않습니다.

## 공통 비수집

- 정확한 좌표와 경로 이력
- 채팅 본문과 원시 명령줄/전체 인자
- IP, 클라이언트 식별 정보, 인벤토리 스냅샷
- 개별 블록 이벤트 시각/좌표와 모든 material 원문

식별자는 `player_uuid`와 편의용 `last_known_name`만 사용합니다.

## 구현됨

| 영역 | 지표/필드 | 저장 단위 | 비용/정확도 |
|---|---|---|---|
| 플레이어 | 최초/최종 관측 시각, 마지막 이름 | player | join/quit 중심, 낮음 |
| 세션 | join/quit, duration, AFK, 시작/종료 월드명 | session | 접속당 1행; reload 시 세션이 분할될 수 있음 |
| 시간 | playtime, AFK | hour; playtime은 day도 저장 | 기본 5초 tick에서 초 단위 분할 집계 |
| 활동성 | active minutes | player x hour | 분당 활동 이벤트 존재 여부 bitset, 0~60 |
| 채팅 | 메시지 수, 문자 수 | player x hour | 본문 직렬화/저장 없음 |
| 명령 | canonical command/variant 실행 수 | player x hour/day | 원시 args 없음; 허용 variant만 저장 |
| 블록 | 설치/파괴 총량 | player x hour | 이벤트당 카운터 1회 |
| 블록 그룹 | group별 설치/파괴 수 | player x day x group | material 대신 고정 그룹으로 축소 |
| 사망 | 사망 수와 cause별 수 | player x day/cause | Bukkit damage cause 키 |
| 처치 | PvP/mob kill 수 | player x day | killer가 플레이어인 death 이벤트 |
| 이동 | 일반 이동 거리 | player x hour | tick 주기 위치 샘플의 직선거리 합; 좌표는 즉시 폐기 |
| 텔레포트 | 횟수와 같은 월드 내 거리 | player x hour/day | 일반 이동과 분리; 월드 간 거리는 0 |

`playtime_sec`는 접속 시간 전체이고 `afk_sec`는 그중 AFK 구간입니다. 둘을 서로 배타적인 값으로 더하면 안 됩니다.

## 활동 이벤트

active minute 및 마지막 활동 시각은 다음 신호로 갱신됩니다.

- 블록 좌표가 바뀌는 이동
- 채팅, 명령 실행
- 블록 설치/파괴
- 텔레포트

고개 회전만으로는 활동 처리하지 않습니다. 인벤토리 클릭은 현재 활동 신호에 포함되지 않습니다.
같은 player/minute의 반복 이벤트는 player state에서 제거해 active-minute bitset lock 갱신은 분당 최대 한 번만 수행합니다.

## 블록 그룹

현재 group key는 다음과 같은 제한된 분류를 사용합니다.

`container`, `ore`, `log`, `redstone`, `rail`, `door`, `wool`, `concrete`, `concrete_powder`, `terracotta`, `glazed_terracotta`, `glass`, `stained_glass`, `planks`, `bricks`, `quartz`, `prismarine`, `sandstone`, `end`, `nether`, `stone`, `ground`, `other`

분류는 `Material`별로 플러그인 로드 시 한 번 계산해 이벤트 경로에서 문자열 규칙을 반복 실행하지 않습니다. `SANDSTONE`, `END_STONE`, `NETHER` 계열처럼 일반 `STONE`/`BRICKS`보다 구체적인 그룹을 먼저 적용합니다.

## 근사치와 데이터 양

- 위치는 기본 5초마다 메모리에서만 비교하므로 왕복·곡선 이동은 실제 경로보다 짧게 집계될 수 있습니다. `tick.intervalSeconds`를 1로 낮추면 정확도는 높아지지만 tick 작업량도 증가합니다.
- 좌표 원본 대신 시간당 정수 거리 1개를 저장하므로 위치 로그 대비 데이터 양 차이는 매우 큽니다.
- 블록은 이벤트 원본 1행이 아니라 시간 총량과 일별 group 카운터만 저장합니다. 따라서 많이 채굴해도 동일 player/day/group/action은 1행입니다.
- 명령도 실행마다 행을 만들지 않고 동일 player/hour/variant를 1행으로 합칩니다.

## 현재 미구현

- 아이템 pickup/drop/craft, 인벤토리 변화
- 월드별 체류 시간과 dimension key
- damage dealt/taken
- TPS/MSPT 서버 상태
- 위험 감지/실시간 경보
- 채팅/IP 옵트인 수집
- 원시 이벤트 또는 이벤트별 연속 WAL

이 항목을 추가할 때는 비용, 카디널리티, 보관정책과 스키마를 함께 정의해야 합니다.

DB flush용 로컬 spool은 구현되어 있으나 새로운 수집 항목은 아닙니다. 위 표의 집계 스냅샷만 일시 저장하며 DB 반영 후 삭제합니다.
