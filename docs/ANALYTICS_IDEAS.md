# Stats — 파생 지표/리포트 아이디어(분석 레이어용)

이 문서는 `docs/DATA_CATALOG.md` / `docs/SCHEMA.md`로 수집한 원본(세션/시간버킷/카운터)을 기반으로, 대시보드/리포트에서 만들 수 있는 지표 아이디어를 정리합니다.

## 1) 활동성/리텐션
- `DAU/WAU/MAU`: day 버킷의 `playtime_sec > 0` 기준
- `Active Minutes`: `fact_player_hour.active_minutes` 기반(“접속만” vs “활동” 구분)
- `AFK Ratio`: `afk_sec / playtime_sec`
- `D1/D7/D30 retention`: 최초 접속일(cohort) 대비 재방문 여부
- `New vs Returning`: 최초 접속 N일 이내/초과 세그먼트
- `Streak`: 연속 접속/연속 “활동일”(AFK 제외) 스트릭, 최장/현재 스트릭
- `Churn Risk(휴면 위험)`: 최근 7일 대비 활동 급감(플레이타임/active_minutes 감소율)

## 2) 시간대/피크 패턴
- `Heatmap`: 요일×시간(`fact_player_hour`)
- `Peak Hour`: 서버 전체 `playtime_sec` 합의 최대 시간대
- `Session Pattern`: 세션 길이 분포(10분 미만/1시간+/3시간+)
- `First Action Latency`: 접속 후 첫 활동(채팅/블록/커맨드)까지 걸린 시간(온보딩 신호)

## 3) 플레이 스타일(가벼운 프로파일링)
- `Builder Index`: `blocks_placed_total + blocks_broken_total` 비중
- `Explorer Index`: `distance_m` 비중 + 월드 체류 다양성(`world_time`)
- `Social Index`: `chat_messages` 및 특정 커맨드(메시지/파티 등) 비중
- `Operator/Staff Activity`: 운영 커맨드 사용(가족/패밀리 기반) 비중
- `Palette Diversity`: (선택 수집) “사용한 블록 그룹 종류 수”로 건축 다양성 추정
- `Routine vs Adventure`: 동일 시간대 반복 접속(루틴) vs 다양한 시간대(어드벤처) 지수

## 4) 커맨드 분석(정규화 효과)
- `Top Commands`: `fact_command_hour` 합산 랭킹
- `Feature Adoption`: 특정 커맨드 패밀리 사용 유저 수/증감
- `WorldEdit Usage`: `worldedit:*` 계열 점유율, 피크 시간대
- `Alias Health`: alias 룰 미적용(label 원문) 비중이 높으면 룰 보강 후보
- `Essentials Mobility`: `home/spawn/tpa` 사용 비율로 이동 패턴(정착형/이동형) 추정
- `WorldEdit Material Trends`: (material 캡처 시) `//set`, `//replace`에서 많이 쓰는 소재 TOP

## 5) 안전/이상 징후(옵션, 오탐 주의)
- `Ore Break Ratio`: ore 그룹 파괴/전체 파괴 비율(자원 서버 전제)
- `Container Break/Place Spike`: 상자/배럴 등 그룹 급증(그리프 탐지 보조 신호)
- `Command Spam`: 시간당 커맨드 수 급증(매크로/봇 의심)
- `AFK Farm`: AFK 비율 높고 블록/아이템 이벤트 동반 패턴(서버 룰에 따라 다름)
- `New Account Burst`: 신규 유저(최초 접속) 급증과 서버 이벤트 상관(홍보/이벤트 효과)
- `Suspicious Night Owls`: 비정상 시간대에만 높은 활동(서버 성격에 따라 다름)

## 6) 서버 운영 리포트
- `Onboarding Funnel`: 첫날 플레이타임/채팅/이동 여부로 온보딩 성공률 추정
- `Content Impact`: 이벤트 기간 전후(주간) KPI 비교
- `World Health`: 월드별 체류시간/활동량(월드 선택/동선 최적화)
- `Community MVP`: (옵션) “활동 꾸준함 + 소셜” 기준 상위 기여자(랭킹은 악용 방지 위해 가중치/비공개 고려)
- `Prime Time Staffing`: 피크 시간대 운영 커맨드/모더레이션 활동량(스태프 배치 참고)

## 7) 관계/커뮤니티(좌표 없이도 가능)
- `Co-Online Overlap`: 세션 시간 겹침(동시 접속)으로 “자주 같이 하는 사람” 추정
  - 데이터: `fact_session`만으로 계산 가능(좌표/파티 정보 불필요)
- `Buddy Graph`: overlap 가중치 기반 유저 그래프/클러스터(커뮤니티 구조)
- `Event Crowd`: 특정 이벤트 시간대에 함께 접속한 유저군(이벤트 운영 피드백)
