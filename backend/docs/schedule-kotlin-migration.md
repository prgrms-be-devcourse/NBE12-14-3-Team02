# Schedule Kotlin 마이그레이션 계획

## 기준과 범위

- 기준 브랜치: `develop` (`cb42c55`). content의 #28, #30, #31, #33 분할 방식을 따른다.
- `kotlin-migration.md`, `ktlint.md`, 프로젝트 README와 기존 content 구현을 기준으로 한다.
- schedule 운영 코드 27개와 전용 예외 8개를 기존 패키지 그대로 Kotlin으로 옮긴다.
- URL, JSON 필드, 검증 메시지, 상태 코드, 정렬·동점 순위, 미응답 null을 유지한다.
- DB 스키마와 비즈니스 로직은 변경하지 않는다. 비동기·코루틴을 도입하지 않는다.
- Java 테스트는 유지하고 접근자 및 nullable repository 호출 문법만 함께 수정한다. 테스트 자체의 Kotlin 전환은 운영 코드 머지 후 별도 작업이다.

## 브랜치 순서

아래 브랜치는 직전 브랜치의 커밋에서 생성하는 누적 브랜치다. 리뷰 시 base를 직전 브랜치로 지정하고, 아래 순서대로 머지한다. 원격 push와 PR 생성은 이 작업에 포함하지 않는다.

| 순서 | 브랜치 | 범위 |
| --- | --- | --- |
| 0 | `docs/kotlin-schedule-plan` | 계획과 검증 기록 |
| 1 | `refactor/kotlin-schedule-entity` | 전용 예외, enum 2개, 엔티티 3개 |
| 2 | `refactor/kotlin-schedule-repository-event` | repository 3개, 마감 이벤트·리스너 |
| 3 | `refactor/kotlin-schedule-dto` | 요청·응답 DTO 8개와 Java record 호출부 |
| 4 | `refactor/kotlin-schedule-candidate-vote` | 후보·투표 서비스와 컨트롤러 |
| 5 | `refactor/kotlin-schedule-poll` | 투표 생성·조회·마감·결과 서비스, 컨트롤러, 스케줄러 |

## 단계별 주의점

1. 엔티티: ID `0L`, 매핑 필드 `var` + `protected set`, 기존 cascade/orphanRemoval/unique 유지. `candidates` JPQL 필드명은 유지하고 외부 getter는 복사본을 제공한다. 기존 Java 호출자가 사용하는 정적 팩토리는 `@JvmStatic`을 유지한다.
2. Repository: 직접 선언한 `Optional` 조회는 nullable 반환으로 전환한다. Java 운영·테스트 호출부도 같은 단계에서 수정한다. 잠금 종류와 JPQL은 그대로 유지한다. 알림은 AFTER_COMMIT + REQUIRES_NEW를 유지한다.
3. DTO: 중첩 data class, 요청 참조 타입 nullable, `@field:` 검증. 응답 companion `from` 유지. 미응답 선호도만 nullable이다.
4. 후보·투표: Meeting → Poll → Member 잠금 순서 및 기존 검증 순서를 유지한다. 검증된 요청은 도메인 전달 지점에서 `checkNotNull`로 꺼낸다.
5. Poll: READ_COMMITTED 마감, 이벤트 발행 시점, 30초 스케줄, 개별 마감 실패 격리를 유지한다. 결과는 점수 내림차순·날짜 오름차순, 동점 경쟁 순위와 회원 ID 정렬을 유지한다.

## 검증

- 변경 전 `ktlintCheck test`로 기준 상태를 확인한다.
- 각 단계에서 `ktlintFormat` 후 범위를 확인하고 `ktlintCheck`와 schedule 테스트를 실행한다. 마지막 단계에서는 필터 없이 `ktlintCheck test`로 전체 회귀 검증을 한다.
- 기존 schedule 단위·API·MySQL 동시성 테스트와 타 도메인 연동 테스트를 실행한다.
- 엔티티 저장 → flush/clear → 재조회, 감사 필드, 지연 로딩, 후보 컬렉션 추가·삭제를 확인하는 회귀 검증을 보완한다.
- DTO 누락·명시적 null·과거 날짜·잘못된 enum의 상태 코드와 메시지를 확인한다.
- 결과와 발견된 제약은 아래 기록에 남긴다.

## 실행 기록

- 계획 수립: 작업 트리 clean, Java 25 설치와 Docker 실행 확인.
- 변경 전 전체 테스트는 로컬 MySQL 미실행으로 연결 실패. `docker compose up -d mysql`로 프로젝트 DB를 시작했다.
- Windows Testcontainers 연결 대기가 길어져 해당 실행을 중단하고, 실행 프로세스에 `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1`을 지정한 뒤 정상 연결을 확인했다. 저장소 설정은 변경하지 않았다.
- 1단계: `ktlintFormat`, `ktlintCheck`, schedule 테스트 **83개 통과**. flush/clear 이후 연관관계·감사 필드·컬렉션 추가·orphanRemoval 회귀 검증을 추가했다.
- 2단계 호환성: Java Mockito의 `any()`는 Kotlin `Long`에서 null 언박싱 오류를 일으켜 해당 ID matcher를 `anyLong()`으로 변경했다. nullable 조회에 맞춰 테스트 fixture와 마감 동시성 spy 반환값도 변경했다.
- 2단계 검증: schedule 83개 중 matcher 때문에 실패한 후보 서비스 테스트를 수정하고 해당 클래스 재실행 통과. 이후 3단계에서 같은 전체 schedule 테스트를 재검증했다.
- 3단계: `ktlintFormat`, `ktlintCheck`, schedule 테스트 **84개 통과**. 마감 시간·후보 날짜·선호도의 명시적 null 요청과 잘못된 enum 검증을 추가했다. Java record 접근자는 Kotlin getter로 변경했다.
- 4단계: `ktlintFormat`, `ktlintCheck`, schedule 테스트 **84개 통과**. Java 호출자가 사라진 후보 엔티티 팩토리와 후보·투표 응답 팩토리의 `@JvmStatic` 제거 후 Java 운영·테스트 재컴파일도 통과했다.
- 5단계: 투표판·결과·마감 서비스, 컨트롤러, 스케줄러 전환. 투표판 응답 팩토리의 불필요한 `@JvmStatic`을 제거했다. `SchedulePoll.create`와 `ScheduleVote.create`는 기존 Java 테스트가 호출하므로 유지한다.
- 최종 검증: `ktlintFormat` 후 필터 없는 `ktlintCheck test` **전체 427개 통과**(schedule 84개 포함, 실패·오류·스킵 0개). Java·Kotlin 혼용 컴파일, 기본 애플리케이션 컨텍스트와 content·meeting 등 타 도메인 테스트도 통과했다. 로컬 보고서는 `build/reports/tests/test/index.html`에서 확인할 수 있다.

### 검증 재실행

Java 25와 Docker가 필요하다. Windows에서 Testcontainers 연결 대기가 길면 현재 PowerShell 프로세스에 아래 설정을 적용한다.

```powershell
$env:TESTCONTAINERS_HOST_OVERRIDE = '127.0.0.1'
docker compose up -d mysql
.\gradlew.bat ktlintFormat
.\gradlew.bat ktlintCheck test
```

브랜치별 변경만 보려면 표의 직전 브랜치를 base로 비교한다. 예를 들어 DTO 리뷰는 `git diff refactor/kotlin-schedule-repository-event...refactor/kotlin-schedule-dto`로 확인한다.
