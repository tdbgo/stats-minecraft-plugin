# Stats - config.yml 가이드

현재 설정 버전은 `4`입니다. 실제 서버 파일은 `plugins/Stats/config.yml`이며, 최초 실행 시 리소스 템플릿을 I/O 전용 스레드에서 생성합니다.

## 최초 실행

- 기본값 `setup.enabled: false`에서는 DB 연결, 스키마 생성, 수집, 업로드를 모두 하지 않습니다.
- DB 설정을 확인한 뒤 `setup.enabled: true`로 변경하고 서버를 재시작하거나 `/stats reload`를 실행합니다.
- reload 초기화가 실패하면 기존 정상 런타임과 기존 수집 버퍼를 유지합니다.

## DB

- `database.type`: `sqlite`, `postgres`, `mysql` 중 하나입니다. 알 수 없는 값은 시작 실패로 처리하며 SQLite로 묵시적 fallback하지 않습니다.
- `database.tablePrefix`: 기본 `mstats_`. 문자/`_`로 시작하고 영문자, 숫자, `_`만 포함하며 `_` 자동 추가 후 최대 24자입니다. 위반하면 활성화를 실패시킵니다.
- `database.queryTimeoutSeconds`: JDBC 쿼리 및 원격 DB socket 제한시간입니다. 허용 범위는 1~300초, 기본 30초입니다.
- `database.sqlite.file`: 플러그인 데이터 폴더 기준 SQLite 파일입니다.
- `database.sqlite.pragmas.*`, `busyTimeoutMs`: WAL, 동기화, FK, lock 대기 설정입니다.
- `database.host`, `port`, `database`, `schema`, `username`, `password`, `ssl.*`: 원격 DB 연결 설정입니다. `schema`는 PostgreSQL에서만 사용합니다.

## 연결 풀

- `database.pool.maximumPoolSize`: 기본 2, 코드상 1~32로 제한합니다. 플러시/핑/리로드 I/O가 단일 executor에서 직렬 처리되므로 큰 풀은 이점이 거의 없습니다.
- `database.pool.minimumIdle`: 기본 0. 무활동 중 원격 연결 유지를 피합니다.
- `connectionTimeoutMs`, `idleTimeoutMs`, `maxLifetimeMs`: HikariCP 설정입니다.
- SQLite는 설정과 무관하게 최대 1개, 최소 idle 0개로 제한합니다.

## 스키마

- `database.migrations.autoCreateTables`: `true`면 스키마와 인덱스를 생성합니다.
- `database.migrations.autoMigrate`: `true`면 지원되는 이전 스키마의 누락 열을 추가합니다.
- 현재 자동 마이그레이션은 schema v2의 텔레포트 열 추가를 포함합니다.
- `autoCreateTables: false`이면 외부에서 DDL을 적용했다는 전제로 자동 검증/생성을 건너뜁니다.

## 수집과 플러시

- `flush.intervalSeconds`: 기본 300초, 허용 범위 10~86,400초입니다. 빈 버퍼는 JDBC 호출 없이 종료합니다.
- `flush.maxBatchRows`: JDBC batch 분할 크기, 허용 범위 1~100,000입니다.
- `tick.intervalSeconds`: playtime, AFK, 이동거리 샘플링 주기, 허용 범위 1~60초입니다.
- `afk.thresholdSeconds`: 마지막 활동 이후 AFK 판정 시간, 허용 범위 5~86,400초입니다.

## 운영 명령

- `commands.enabled`: Stats 관리 명령 전체를 켜거나 끕니다.
- `commands.allowReload`: `/stats reload`
- `commands.allowDbPing`: `/stats db ping`
- `commands.allowForceFlush`: `/stats flush`

모든 관리 명령은 `stats.admin` 권한 또는 OP가 필요합니다.

## 비수집 설정

v4에서는 구현되지 않은 `riskDetection.*`, `privacy.*`, `logging.*` 템플릿 키를 제거했습니다. 이전 config에 남아 있어도 무시됩니다. 이 수집기는 설정과 관계없이 채팅 본문, IP, 좌표, 인벤토리 스냅샷을 저장하지 않으며 위험 경보는 외부 분석 계층의 책임입니다.
