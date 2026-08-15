# Stats - 운영 명령과 체크리스트

모든 명령은 `stats.admin` 권한 또는 OP가 필요하며, `commands.enabled`와 각 `allow*` 설정의 영향을 받습니다.

## 최초 활성화

1. 서버를 한 번 실행해 `plugins/Stats/config.yml`과 `command-aliases.yml`을 생성합니다.
2. DB 설정을 확인하고 `setup.enabled: true`로 변경합니다.
3. 서버 재시작 또는 `/stats reload`를 실행합니다.
4. `/stats status`, `/stats db ping`, `/stats db health`로 상태를 확인합니다.

안전 모드에서는 DB 연결도 만들지 않습니다.

## 구현된 명령

- `/stats reload`
  - config와 alias 파일 읽기, 유효성 검사, DB 풀/스키마 초기화를 `Stats-IO`에서 수행합니다.
  - 새 후보가 완전히 준비된 뒤 메인 스레드에서 교체합니다.
  - 실패하면 기존 런타임을 계속 사용합니다.
  - 기존 버퍼와 이미 실패한 배치는 retired queue에서 같은 DB로 계속 플러시합니다.
  - DB type/prefix 변경은 데이터 이관이 아닙니다. 이전 컨텍스트는 이전 DB에 남은 배치를 먼저 기록하고 닫습니다.
- `/stats status`
  - 플러그인 버전, active/initializing, DB type/prefix, 현재 보류 행 수, durable pending batch 수, retired runtime 수, 최근 reload/flush 시각과 오류를 표시합니다.
- `/stats db ping`
  - I/O executor에서 `SELECT 1`을 실행하고 지연시간을 출력합니다.
- `/stats db health`
  - Hikari pool의 active/idle/total/awaiting connection 수를 표시합니다. SQL을 실행하지 않습니다.
- `/stats flush`
  - 즉시 비동기 플러시를 예약합니다. 이미 실행/대기 중이면 중복 예약하지 않습니다.

임의 SQL, export/import, test-write, migration 조회 명령은 현재 구현되어 있지 않습니다.

## 300초 무활동 확인

접속자와 이벤트가 모두 없으면 300초마다 로컬 timer/executor만 실행되고 JDBC 연결은 요청하지 않습니다. 원격 연결을 무활동 중 유지하지 않으려면 v4 기본값처럼 다음을 사용합니다.

```yaml
database:
  pool:
    maximumPoolSize: 2
    minimumIdle: 0
```

기존 v3 config에 10/2가 명시돼 있으면 자동으로 덮어쓰지 않으므로 직접 변경해야 합니다.

## 장애와 종료

- 플러시 실패 시 `/stats status`의 `lastFlushError`와 서버 로그를 확인합니다.
- 실패 배치는 메모리와 `plugins/Stats/spool/<storage-id>/`에 유지되므로 DB 복구 후 `/stats flush`로 즉시 재시도하거나 다음 기동에서 자동 재생할 수 있습니다.
- 같은 snapshot 재시도는 `ingest_batch.batch_id`로 중복 합산을 막습니다.
- 정상 plugin disable은 온라인 세션을 마감하고 비동기 최종 플러시를 예약합니다. DB 장애 시 최종 batch는 spool에 남습니다.
- spool 손상/체크섬 오류는 파일을 보존한 채 활성화를 실패시킵니다. 해당 파일을 임의 삭제하기 전에 복사본과 서버 로그를 확보합니다.
- 아직 5분 스냅샷으로 drain되지 않은 활성 메모리 버퍼는 강제 프로세스 종료 시 유실될 수 있습니다.

## 업데이트 체크리스트

1. Java 25와 Paper `26.2` build 111 이상을 준비하고 현재 config의 `config-version`과 v4 변경점을 확인합니다.
2. DB를 백업합니다. 특히 v1에서 v2는 player hour/day에 열을 추가합니다.
3. 새 JAR로 정상 재시작하고 schema migration 및 durable batch recovery 로그에 오류가 없는지 확인합니다.
4. `/stats status`와 `/stats db ping`을 확인합니다.
5. 테스트 활동 후 `/stats flush`를 실행하고 fact/ingest 테이블 증가를 확인합니다.

SQLite에서 PostgreSQL/MySQL로 옮기는 절차는 `docs/DATA_MIGRATION.md`를 따르되, 현재 플러그인에 export/import 명령이 없으므로 외부 이관 도구가 필요합니다.
