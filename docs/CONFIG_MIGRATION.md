# Stats - config 호환과 마이그레이션

현재 최신 `config-version`은 `4`입니다.

## 현재 동작

- 서버의 `config.yml`을 읽고 번들 기본 config를 defaults로 겹칩니다.
- 기존에 없는 키는 메모리상 기본값을 사용하지만 서버 파일을 자동 재작성하지 않습니다.
- 버전 1~4는 읽을 수 있습니다. 1 미만 또는 플러그인보다 새로운 버전은 활성화를 실패시킵니다.
- 타입/범위는 runtime `Settings` 생성 시 검증하거나 안전 범위로 제한합니다.
- reload 검증이나 DB 초기화가 실패하면 새 후보 컨텍스트를 닫고 기존 런타임을 계속 사용합니다.

따라서 현재 구현은 백업 파일 생성, 주석 보존 병합, config 파일의 원자적 자동 변환을 제공하지 않습니다. 자동으로 파일을 바꾼다는 전제에서 운영하면 안 됩니다.

## v3에서 v4

- `database.queryTimeoutSeconds` 추가, 키가 없으면 30초
- 새 템플릿의 원격 DB 풀 기본값을 `maximumPoolSize: 2`, `minimumIdle: 0`으로 축소
- 누락돼 있던 `tick.intervalSeconds`, `afk.thresholdSeconds`를 배포용 config에도 동기화
- 구현되지 않은 `riskDetection.*`, `privacy.*`, `logging.*` 템플릿 키 제거

기존 파일에 이미 `maximumPoolSize: 10`, `minimumIdle: 2`가 있으면 사용자 설정으로 간주해 그대로 사용합니다. 저유휴 동작을 적용하려면 운영 config에서 직접 2/0으로 변경해야 합니다. 제거된 키가 남아 있어도 무시됩니다.

## 이전 버전 기본값

- `setup.enabled`가 없으면 `false`
- `database.tablePrefix`가 없으면 `mstats_`
- `database.type`이 없으면 `sqlite`
- tick/AFK/query timeout이 없으면 각각 5초/60초/30초

## 향후 자동 저장 마이그레이션 요건

자동 파일 마이그레이션을 추가할 경우에는 다음을 함께 구현해야 합니다.

1. timestamp가 붙은 백업 생성
2. 버전별 순차 변환
3. 임시 파일 작성 후 원자적 rename
4. 실패 시 원본 유지
5. 민감값을 로그에 출력하지 않는 diff 요약

Bukkit `YamlConfiguration`은 주석 보존에 한계가 있으므로, 주석 보존이 요구되면 전용 YAML 라이브러리 도입이 필요합니다.
