# 백엔드 테스트 CI 적용 기록

## 적용 배경

기존 `.github/workflows/backend-ktlint.yml`은 PR마다 `ktlintCheck`만 실행했다. Kotlin 코드 형식은 검사했지만, 컴파일 오류나 기존 테스트의 실패를 GitHub에서 자동으로 확인하지는 않았다.

이미 작성된 단위·API·DB 통합·동시성 테스트를 PR마다 실행하도록 별도 테스트 워크플로를 추가했다. 기존 테스트의 assertion과 서비스 로직은 변경하지 않았다.

## 변경한 파일

| 파일 | 변경 내용 | 이유 |
| --- | --- | --- |
| `.github/workflows/backend-test.yml` | 테스트 실행 워크플로 추가 | PR 생성·추가 커밋 시 전체 백엔드 테스트를 자동 실행한다. |
| `backend/src/test/java/com/prgms/backend/BackendApplicationTests.java` | `MySqlContainerSupport` 상속 | 로컬 `.env` 없이 임시 MySQL과 테스트용 JWT 키로 애플리케이션을 기동한다. |
| `backend/docs/backend-test-ci.md` | 적용 과정과 확인 방법 기록 | 팀원 공유와 발표 자료로 사용한다. |

기존 lint 워크플로와 공통 컨테이너 지원 클래스는 수정하지 않았다.

## 실행 흐름

```text
PR 생성 또는 PR 브랜치에 추가 커밋
  → GitHub가 임시 Ubuntu 실행 환경 준비
  → 저장소 코드 체크아웃
  → Temurin Java 25 준비
  → Docker 사용 가능 여부 확인
  → backend에서 ./gradlew test --no-daemon --console=plain 실행
  → 테스트 보고서를 artifact로 보관
```

기존 lint 워크플로도 별도로 실행된다. `workflow_dispatch`는 수동 실행을 위한 설정이다. GitHub의 수동 실행 메뉴는 워크플로가 기본 브랜치에 반영된 뒤 확인한다.

| 설정 | 이 프로젝트에서의 의미 |
| --- | --- |
| Java 25 | `backend/build.gradle`의 Java toolchain과 일치시킨다. |
| `working-directory: backend` | 저장소 루트가 아닌 Gradle 프로젝트 폴더에서 명령을 실행한다. |
| `docker info` | Docker를 사용할 수 없는 환경에서 DB 통합 테스트가 생략되는 상황을 줄이기 위해 먼저 확인한다. |
| `./gradlew test` | Java·Kotlin 운영 코드와 테스트 코드를 컴파일하고 기존 JUnit 테스트를 실행한다. |
| `--no-daemon` | 임시 CI 환경에서 재사용할 Gradle 데몬을 유지하지 않는다. |
| Gradle 캐시 | 의존성을 매번 새로 내려받는 작업을 줄인다. 테스트 성공 여부를 대신 판단하는 설정은 아니다. |
| `timeout-minutes: 20` | 테스트 작업이 끝나지 않으면 최대 실행 시간을 제한한다. |
| `concurrency` | 같은 PR에 새 커밋이 올라오면 이전 테스트 실행을 취소하고 최신 커밋을 검사한다. |
| 보고서의 `if: always()` | 테스트 단계가 실패해도 만들어진 보고서를 업로드한다. |
| `retention-days: 7` | 업로드된 보고서를 7일간 보관한다. |

보고서 경로는 `backend/build/reports/tests/test/`와 `backend/build/test-results/test/`다. `defaults.run.working-directory`는 `run` 명령에 적용되므로, 업로드 액션에는 저장소 루트를 기준으로 `backend/`까지 적었다.

## 로컬에서는 통과하던 테스트를 수정한 이유

`BackendApplicationTests`는 원래 `@SpringBootTest`만 사용했다. 이 테스트가 읽는 `application.yml`은 `.env`의 DB 접속 정보와 JWT 키를 참조한다.

개발자 PC에는 `.env`와 실행 중인 DB가 있어서 통과할 수 있지만, `.env`는 Git으로 공유되지 않는다. 따라서 GitHub에서 저장소만 체크아웃하면 같은 조건을 재현할 수 없었다.

이를 확인하려고 `git archive HEAD backend`로 Git에 추적되는 백엔드 파일만 임시 폴더에 복사했다. 원본 `.env`는 이동하거나 수정하지 않았다. 이 복사본에서 수정 전 `BackendApplicationTests`를 실행하자, `${MYSQLUSER}`가 실제 접속 정보로 치환되지 않아 Flyway의 DB 접속 단계에서 실패했다.

해결에는 이미 다른 테스트가 사용하던 `MySqlContainerSupport`를 재사용했다.

```java
@SpringBootTest
class BackendApplicationTests extends MySqlContainerSupport {
    // 기존 contextLoads 테스트 유지
}
```

공통 지원 클래스가 제공하는 것은 다음과 같다.

- Testcontainers가 실행하는 테스트용 `mysql:8.0` 컨테이너
- `@DynamicPropertySource`로 등록하는 컨테이너의 실제 DB 주소·계정·비밀번호
- 테스트용 JWT access·refresh 서명 키

이 테스트도 Flyway 마이그레이션과 `ddl-auto=validate`를 사용한다. DB 연결만 성공시키는 것이 아니라, 애플리케이션 기동 시 마이그레이션과 엔티티 매핑 검증도 함께 실행한다.

GitHub Secrets에 개발용 DB 비밀번호나 운영 JWT 키를 넣을 필요는 없다. 또한 기존 테스트가 Testcontainers로 DB를 생성하므로 워크플로에 별도의 MySQL 서비스 컨테이너를 중복 추가하지 않았다.

## 검증 과정

1. 기존 상태에서 전체 테스트 446개가 통과하는 기준을 확인했다.
2. Git에 추적되는 파일만 복사한 `.env` 없는 임시 작업 폴더에서, 수정 전 `BackendApplicationTests`의 DB 접속 실패를 재현했다.
3. 동일한 복사본에 수정한 테스트 파일을 반영하고 `ktlintCheck test --no-daemon --console=plain`으로 전체 검증을 실행했다.
4. 실제 작업 폴더의 기존·신규 워크플로를 actionlint 1.7.12로 검사했다. GitHub Actions 문법·식 검사를 수행했으며, 로컬에 없는 shellcheck·pyflakes 검사는 제외했다.

2026-10-26 검증 결과:

| 검증 | 결과 |
| --- | --- |
| 수정 전 `.env` 없는 환경의 `BackendApplicationTests` | 테스트 1개 실패, Flyway DB 접속 오류 재현 |
| 수정 후 같은 환경의 `ktlintCheck` | 통과 |
| 수정 후 같은 환경의 전체 테스트 | 56개 테스트 스위트, 446개 테스트, 실패 0·오류 0·건너뜀 0 |
| 기존·신규 워크플로 actionlint 검사 | 통과 |

검증한 테스트 파일과 실제 작업 폴더의 테스트 파일은 SHA-256 해시로 동일함을 확인했다. 임시 작업 폴더에 `.env`가 없다는 점도 확인했다.

로컬 검증은 Windows와 Docker에서 수행했다. GitHub의 Ubuntu 실행 결과와 artifact 업로드 성공 여부는 PR을 올린 뒤 별도로 확인해야 한다.

## PR을 올린 뒤 확인할 것

1. PR의 Checks에서 기존 `Backend ktlint`와 새 `Backend tests` 워크플로가 실행되는지 확인한다.
2. `Backend tests`의 `test` 작업이 성공하는지, DB 통합 테스트가 건너뛰어지지 않았는지 로그와 XML 보고서를 확인한다.
3. Actions 실행 화면의 Artifacts에서 `backend-test-reports`를 내려받는다. 압축을 푼 뒤 HTML 보고서의 `index.html`에서 테스트 결과를 확인한다.
4. 실패하면 `Run backend tests` 로그와 XML·HTML 보고서에서 실패한 테스트와 원인을 찾는다. 수정 커밋을 같은 PR 브랜치에 push하면 다시 실행된다.

테스트 CI를 추가하는 것과 실패한 PR의 머지를 강제로 막는 것은 별도 설정이다. 강제 차단이 필요하면 저장소 관리자가 `develop`의 브랜치 보호 규칙 또는 Ruleset에서 첫 실행으로 등록된 lint·test 상태 검사를 필수로 지정해야 한다. 이 작업에서는 저장소 설정을 변경하지 않았다.

## 이번 작업에서 배운 점

- **CI는 기존 검증을 공통 환경에서 자동으로 반복하는 과정이다.** 이번에는 테스트를 새로 대량 작성한 것이 아니라, 이미 있는 테스트를 PR마다 실행하도록 연결했다.
- **lint 통과와 동작 검증은 다르다.** ktlint는 코드 형식을 검사하고, 테스트는 작성된 시나리오의 기대 결과가 유지되는지 확인한다.
- **내 PC에서 통과해도 다른 환경에서 통과한다는 보장은 없다.** 로컬 `.env`와 개인 DB에 의존하던 테스트를 찾아 테스트 전용 환경으로 분리했다.
- **DB 테스트에는 재현 가능한 DB 환경이 필요하다.** 기존 Testcontainers 구성을 재사용해 CI가 테스트용 MySQL을 직접 준비하도록 했다.
- **실패 결과도 남겨야 원인을 찾을 수 있다.** 실패 후에도 보고서를 업로드하도록 구성했다.
- **테스트 성공은 테스트가 다루는 범위의 근거다.** 모든 마이그레이션 동작이 완전히 같다는 증명이나 성능 개선 측정 결과는 아니다.

발표에서는 다음과 같이 설명할 수 있다.

> 기존 CI는 ktlint만 검사해서 PR에서 컴파일 오류나 테스트 실패를 자동으로 확인하지 못했습니다. 기존 테스트를 GitHub Actions에서 실행하도록 추가했고, 로컬 .env에 의존하던 애플리케이션 기동 테스트는 공통 Testcontainers 설정을 재사용하도록 수정했습니다. Git으로 공유되는 파일만 복사한 환경에서 기존 실패를 재현하고, 수정 후 같은 환경에서 전체 테스트를 검증했습니다. 이를 통해 코드뿐 아니라 테스트 환경도 재현 가능해야 한다는 점을 배웠습니다.

## 참고 자료

- [GitHub: Java·Gradle 빌드와 테스트](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-gradle)
- [Testcontainers: 컨테이너 실행 환경 요구사항](https://java.testcontainers.org/supported_docker_environment/)
- [GitHub: upload-artifact 액션](https://github.com/actions/upload-artifact)
- [GitHub: 워크플로 수동 실행](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)
- [GitHub: 보호된 브랜치와 필수 상태 검사](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
