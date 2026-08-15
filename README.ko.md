# Stats

**Stats by PLAYCITY BLOCK**

[English](README.md)

Stats는 Paper 서버의 플레이어 활동을 세션, 시간, 일 단위의 저카디널리티 집계로 저장하는 서버 전용 플러그인입니다. 대시보드와 오프라인 통계 작성에 사용할 수 있습니다.

## 수집 범위

세션 시간, AFK 시간, 사망, 처치, 텔레포트, 재료 그룹, 정규화된 명령 분류를 저장합니다. 좌표, 채팅 본문, 원시 명령줄, IP 주소, 인벤토리 스냅샷은 저장하지 않습니다.

전체 수집 범위는 [DATA_CATALOG.md](docs/DATA_CATALOG.md)를 참고하십시오.

## 지원 환경

- Minecraft 및 Paper 26.2 build 112
- Java 25
- SQLite, PostgreSQL, MySQL, MariaDB

Paper 26.2 서버만 검증합니다. 클라이언트에는 설치하지 않습니다.

## 설치

1. `Stats-0.3.2.jar`를 Paper의 `plugins` 디렉터리에 넣습니다.
2. 서버를 한 번 시작하면 `setup.enabled: false` 상태의 설정 파일이 생성됩니다.
3. 데이터베이스와 수집 설정을 검토합니다.
4. `setup.enabled: true`로 변경한 뒤 서버를 정상 재시작하거나 `/stats reload`를 실행합니다.

Paper는 최초 기동 시 Maven Central에서 MariaDB Connector/J 3.5.2를 별도 플러그인 라이브러리로 내려받아 캐시합니다. 캐시에 해당 파일이 없는 완전 오프라인 최초 설치는 지원하지 않습니다.

## 데이터 목적지와 네트워크 동작

SQLite 데이터는 Stats 플러그인 데이터 디렉터리에 저장됩니다. PostgreSQL, MySQL, MariaDB를 선택하면 서버 관리자가 지정한 DB 호스트로만 데이터가 전송됩니다. Stats에는 자체 텔레메트리, 업데이트 확인, 분석 엔드포인트, 기타 HTTP 서비스가 없습니다.

Paper는 외부 MariaDB 드라이버를 가져오기 위해 Maven Central에 접속할 수 있습니다. 그 외에 Stats가 여는 네트워크 연결은 설정된 JDBC 데이터베이스 연결뿐입니다.

## 명령어

모든 명령은 `stats.admin` 권한 또는 OP 권한이 필요합니다.

- `/stats reload`
- `/stats status`
- `/stats db ping`
- `/stats db health`
- `/stats flush`

설정과 운영 절차는 [CONFIG.md](docs/CONFIG.md), [OPERATIONS.md](docs/OPERATIONS.md)를 참고하십시오.

## 빌드

```text
./gradlew clean test build
```

결과물은 `build/libs/Stats-0.3.2.jar`입니다.

## 라이선스

프로젝트 자체 작성 소스, 테스트, 문서, 리소스는 [MIT License](LICENSE)로 제공되며 법적 권리자는 `PLAYCITY`입니다. 제3자 구성요소에는 각 구성요소의 라이선스가 적용됩니다. MariaDB Connector/J는 Stats JAR에 포함되지 않습니다.
