# Stats - 명령어 정규화와 인자 정책

목표는 같은 기능의 alias를 하나의 저카디널리티 키로 합치면서 원시 명령줄과 민감 인자를 저장하지 않는 것입니다.

## 현재 수집 경로

`PlayerCommandPreprocessEvent`에서 실행 전 명령줄을 받지만 DB에는 다음 결과만 집계합니다.

- `command_key`: canonical label, 예 `minecraft:gamemode`, `worldedit:replace`
- `variant_key`: 허용된 저카디널리티 값만, 예 `mode=creative`
- 시간/일 버킷별 실행 횟수

원시 명령줄, 전체 args, 대상 플레이어명, 메시지 본문, 좌표는 스냅샷과 DB 어디에도 보관하지 않습니다.

## 정규화 순서

1. 앞뒤 공백 제거
2. 일반 명령의 선행 `/` 제거; WorldEdit `//`는 유지
3. 소문자화 후 공백 기준 토큰 분리
4. 첫 토큰 label을 `command-aliases.yml` 정규식과 순서대로 비교
5. 일치하면 `canonical`, 없으면 namespace가 없는 label에 `minecraft:`를 붙임
6. label/canonical이 255자를 넘으면 수집하지 않음
7. rule이 허용한 `safe_args`만 코드의 제한된 파서로 변환

런타임 리소스 `src/main/resources/command-aliases.yml`과 운영 참고본 `config/command-aliases.yml`은 동일하게 유지합니다. 서버에 최초 복사된 `plugins/Stats/command-aliases.yml`은 이후 자동 덮어쓰기하지 않습니다.

## 지원 safe_args

- `mode`: `survival|creative|adventure|spectator`, 단축값과 숫자 0~3만 허용
- `material`: Bukkit `Material.matchMaterial`로 정확히 해석되는 첫 인자만 허용
- `target_kind`: sender 자신이면 `self`, 그 외 문자열은 `other`; 이름 자체는 저장하지 않음
- `home_kind`: 인자 없음 `default`, 있음 `other`

설정에 임의 safe_args 이름을 넣어도 저장되지 않습니다. 여러 safe_args가 있으면 뒤에서 유효하게 해석된 값이 variant를 결정하므로 한 rule에는 보통 하나만 둡니다.

## 예시

- `/gmc` -> `minecraft:gamemode`, `mode=creative`
- `/gamemode 1 SomePlayer` -> `minecraft:gamemode`, `mode=creative`; 플레이어명 폐기
- `//set stone` -> `worldedit:set`, `material=stone`
- `/msg Alice hello` -> `essentials:msg`, `target_kind=other`; 대상과 본문 폐기
- `/tp 10 64 10` -> `essentials:tp`, 빈 variant; 좌표 폐기
- 등록되지 않은 `/warp private-home` -> `minecraft:warp`, 빈 variant; 인자 폐기

## 운영 주의

- 규칙은 첫 일치가 우선이므로 구체적인 패턴을 앞에 둡니다.
- 패턴 입력은 관리자 신뢰 설정이지만 label은 255자로 제한됩니다. 불필요하게 복잡한 정규식은 사용하지 않습니다.
- alias 파일 문법 오류는 reload 실패로 처리되고, 기존 정상 런타임은 유지됩니다.
