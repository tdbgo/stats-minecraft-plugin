# Stats 0.3.0

## 릴리스 기준

- Paper `26.2` build 111
- Java 25
- Gradle 9.6.1 wrapper
- Shadow 9.6.1

## 포함

- 비-SNAPSHOT 릴리스 버전과 descriptor/API 버전 일치
- DB 전송 전 집계 snapshot의 체크섬·원자 이동 기반 durable spool
- DB 장애 또는 종료 flush 실패 batch의 다음 기동 자동 재생
- 손상 spool 보존 및 안전한 활성화 실패
- `/stats status`의 durable pending batch 표시
- Gradle 9용 JUnit Platform launcher와 Java 25 테스트 실행 설정

기존 DB 스키마, 명령, 권한, config 키는 변경하지 않습니다. `config-version`은 4를 유지합니다.

## 과거 논의 중 보류

- 원시 명령줄·전체 인자와 개별 블록 원본: 개인정보, 카디널리티, 저장량 증가 때문에 기존 정규화 집계 정책을 유지합니다.
- 이벤트별 연속 WAL: 5분 사이 강제 종료까지 막을 수 있지만 이벤트 hot path 파일 I/O와 저장량 설계가 필요해 보류합니다.
- 위험 감지와 실시간 경보: 오탐 방지, 권한/보호구역 연동, 5분 세부 bucket 설계가 선행되어야 하므로 분석 레이어 과제로 유지합니다.
- config 파일 자동 재작성 마이그레이션: 주석 보존 YAML과 백업·원자 교체 정책이 필요해 현재의 런타임 default 병합을 유지합니다.
- 통계 시각화, 장기 rollup, Plan/FAWE 백필 자동화: collector 릴리스와 분리된 배치/웹 계층 범위로 유지합니다.
