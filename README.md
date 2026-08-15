# Stats

**Stats by PLAYCITY BLOCK**

Stats는 Paper 서버의 플레이어 활동을 세션, 시간, 일 단위의 저카디널리티 집계로 저장하는 서버 플러그인입니다. 좌표, 채팅 본문, 원시 명령줄, IP 주소, 인벤토리 스냅샷은 수집하지 않습니다.

## 지원 환경

- Paper 26.2 build 111 이상
- Java 25
- SQLite, PostgreSQL, MySQL, MariaDB

현재 공개 소스 버전은 `0.3.1`입니다. Paper 26.2 build 112와 PostgreSQL 환경에서 기동 및 연결 초기화를 검증했습니다.

## 빌드

```text
./gradlew clean test build
```

Windows에서는 `gradlew.bat`을 사용할 수 있습니다. 결과물은 `build/libs/Stats-0.3.1.jar`에 생성됩니다.

최초 실행은 `setup.enabled: false`인 안전 모드로 설정 파일만 생성합니다. 설정을 검토한 뒤 값을 `true`로 변경하고 서버를 정상 재시작하거나 `/stats reload`를 실행해야 데이터베이스 연결과 수집을 시작합니다.

## 명령어

모든 명령은 `stats.admin` 권한 또는 OP 권한이 필요합니다.

- `/stats reload`
- `/stats status`
- `/stats db ping`
- `/stats db health`
- `/stats flush`

설정과 운영 절차는 [docs/CONFIG.md](docs/CONFIG.md), [docs/OPERATIONS.md](docs/OPERATIONS.md), 수집 범위는 [docs/DATA_CATALOG.md](docs/DATA_CATALOG.md)를 참고하십시오.

## 라이선스와 바이너리 재배포

Stats 자체 작성 소스, 테스트, 문서, 리소스는 [MIT License](LICENSE)로 제공되며 법적 권리자 표기는 `PLAYCITY`입니다. 제품 byline과 publisher 표기는 `Stats by PLAYCITY BLOCK` 및 `PLAYCITY BLOCK`입니다.

프로젝트 MIT 라이선스는 Gradle wrapper나 데이터베이스 드라이버 등 제3자 구성요소의 라이선스를 대체하지 않습니다. 기본 Shadow 빌드는 제3자 라이브러리를 단일 fat JAR에 포함합니다. 이 저장소는 컴파일된 Stats JAR을 배포하지 않으며, fat JAR을 재배포하려면 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)와 [licenses](licenses) 디렉터리의 조건을 함께 확인해야 합니다. 특히 MariaDB Connector/J는 LGPL-2.1-or-later이므로 프로젝트 MIT만으로 JAR을 단독 재배포해서는 안 됩니다.
