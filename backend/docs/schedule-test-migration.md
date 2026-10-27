# Schedule 테스트 Kotlin 마이그레이션

## 범위와 기준

`kotlin-migration.md`, `ktlint.md`, `backend-test-ci.md`를 기준으로 Schedule 테스트 클래스 8개와 공통 fixture 1개를 같은 패키지의 `src/test/kotlin`으로 옮겼다. 운영 코드와 빌드 설정은 변경하지 않았다.

기준 커밋은 `b036091`이다. 기존 테스트 메서드 65개, 파라미터화 실행을 포함한 84건의 검증 의도와 기대 결과를 유지한다. 클래스별 assertion, Mockito 검증, MockMvc 응답 검증 및 오류 검증 helper 호출 수도 원본과 대조했다. 호출 수 비교만으로 동등성을 판단하지 않고 실제 MySQL 통합 테스트도 실행했다.

## 브랜치와 병합 순서

각 브랜치는 앞 단계의 커밋을 포함한다. PR을 나누면 아래 base 순서로 생성하고, 앞 PR 병합 후 다음 PR의 base를 `develop`으로 변경한다.

| 순서 | 브랜치 | 최초 PR base | 작업 |
| --- | --- | --- | --- |
| 1 | `refactor/schedule-test-foundation` | `develop` | 공통 fixture, Poll 엔티티, 마감 스케줄러 테스트 |
| 2 | `refactor/schedule-test-services` | `refactor/schedule-test-foundation` | Poll·Candidate·Vote·Result 서비스 테스트, fixture의 임시 Java 호환 애노테이션 제거 |
| 3 | `refactor/schedule-test-integration` | `refactor/schedule-test-services` | API·JPA 재조회·동시성 테스트, Schedule 테스트 import 정리, 검증 기록 |

첫 단계에서는 남아 있는 Java 서비스 테스트가 fixture를 호출하므로 `@JvmStatic`을 유지했다. 두 번째 단계에서 Java 호출자가 사라지는 것을 확인하고 제거했다. Content의 Kotlin 테스트도 같은 fixture를 사용하므로 함께 검증했다.

## 유지한 검증 범위

| 테스트 | 실행 건수 | 주요 검증 |
| --- | --- | --- |
| `SchedulePollTest` | 2 | 후보 10개 제한, 11번째 거절 후 기존 후보 유지 |
| `SchedulePollCloseSchedulerTest` | 1 | 한 투표 마감 실패 후 다음 투표 처리 |
| `SchedulePollServiceTest` | 8 | 생성·중복·권한·모임 상태·조회·마감 시간 수정 |
| `ScheduleCandidateServiceTest` | 7 | 후보 등록·수정·삭제, 중복 날짜, 투표 존재, 권한·마감 상태 |
| `ScheduleVoteServiceTest` | 5 | 첫 투표 저장, 재투표 수정, 탈퇴자·다른 Poll 후보·마감 투표 거절 |
| `ScheduleResultServiceTest` | 2 | 공동 순위·총점·0점 응답·미응답·탈퇴자 제외, 마감 전 결과 거절 |
| `ScheduleControllerIntegrationTest` 및 nested 테스트 | 53 | 인증·권한, 상태 코드·응답 본문, 누락·명시적 null·잘못된 입력의 오류 종류·메시지, 저장 후 재조회와 연관관계·감사 필드·컬렉션·orphan removal |
| `ScheduleConcurrencyIntegrationTest` | 6 | 후보 제한 경합, 투표와 후보 수정·삭제 경합, 같은 회원의 첫 투표 직렬화, 마감 도중 가입자의 알림, 알림 실패와 마감 커밋 분리 |
| 합계 | 84 | 원본과 전환본의 클래스별 실행 건수 일치 |

## Kotlin 전환 시 처리한 사항

- 테스트 이름은 기존 설명을 백틱 함수명으로 옮기고, `@Nested` 클래스는 `inner class`로 선언했다.
- Mockito는 기존 도구를 유지하면서 mockito-kotlin의 `mock`, `whenever`, `any`, `doAnswer`, `doThrow`를 사용한다.
- 엔티티의 ID 설정은 기존처럼 fixture 안의 `ReflectionTestUtils`로 수행한다.
- 결과 집계의 `Long` 기대값을 `2L`처럼 명시해 Java 숫자 오버로드와 같은 값을 검증한다.
- JPA 기본 조회는 `findByIdOrNull`과 `checkNotNull`로 처리하고 `!!`는 사용하지 않는다.
- Testcontainers 컨테이너는 companion object의 `@JvmField`, Spring의 `@DynamicPropertySource`는 `@JvmStatic`으로 등록한다. 이 애노테이션들은 프레임워크의 정적 필드·메서드 탐색에 필요하므로 유지한다.
- 동시성 테스트의 격리 수준 확인, 트랜잭션 경계, latch 순서, 제한 시간, `Future.get()` 예외 전파 및 `finally` 정리를 유지했다. 반환값이 있는 작업은 명시적인 `Callable`로 제출한다.
- 기존 테스트의 MySQL 이미지, 프로필, Flyway·DDL 설정, 스케줄러 mock, Docker 부재 시 동작은 유지했다.

## 검증 방법

Java 25와 실행 중인 Docker 엔진이 필요하다. `backend` 폴더에서 실행한다.

```powershell
.\gradlew.bat ktlintFormat
.\gradlew.bat ktlintCheck test --tests 'com.prgms.backend.domain.schedule.*'
.\gradlew.bat ktlintCheck test
```

`ktlintFormat` 실행 후에는 다른 도메인 파일이 변경되지 않았는지 확인한다. 테스트 보고서는 `build/reports/tests/test/index.html`과 `build/test-results/test/`에 생성된다. Docker를 사용할 수 없으면 일부 동시성 테스트가 건너뛰어질 수 있으므로 XML의 `skipped`도 확인한다.

## 검증 결과

2026-10-27, Windows·Java 25·Docker·MySQL 8.0에서 확인했다.

| 대상 | 결과 |
| --- | --- |
| 원본 커밋의 Schedule 테스트 (별도 작업 폴더, `.env` 없음) | 84건, 실패 0·오류 0·건너뜀 0 |
| 첫 단계의 Schedule 단위 테스트 및 fixture를 사용하는 Content 서비스 테스트 | 52건, 실패 0·오류 0·건너뜀 0 |
| Kotlin으로 전환한 Schedule 서비스 테스트 | 22건, 실패 0·오류 0·건너뜀 0 |
| Kotlin으로 전환한 Schedule 전체 테스트 | 84건, 실패 0·오류 0·건너뜀 0 |
| 최종 소스의 `ktlintFormat` → `ktlintCheck test` | lint 통과, 전체 59개 스위트·480건, 실패 0·오류 0·건너뜀 0 |

최종 전체 실행에서도 Schedule의 클래스별 실행 건수가 원본과 일치했다. Java 컴파일 산출물 경로에 Schedule 테스트 클래스가 남아 있지 않고, 보고서에 Kotlin의 백틱 테스트명이 기록되는 것도 확인했다.

원본 실행과 작업 폴더 검증의 보고서 경로가 겹치지 않도록 원본은 별도 worktree에서 실행했다. GitHub Actions 실행과 원격 PR 생성은 이 로컬 검증에 포함되지 않는다.
