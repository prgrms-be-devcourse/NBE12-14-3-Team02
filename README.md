
# "모임 구축해주겠어"

여러 명이 함께하는 모임에서 흩어지기 쉬운 **일정 조율, 콘텐츠 투표, 지출 및 정산 과정**을 하나의 모임 안에서 관리할 수 있도록 돕는 서비스입니다.


[2차 프로젝트 - Java](https://github.com/prgrms-be-devcourse/NBE12-14-2-Team02)
[ERD](https://www.erdcloud.com/d/4JszcdGazFPbWMPTT)  

---

## 📌 프로젝트 소개

모임을 준비할 때는 가능한 날짜를 맞추고, 무엇을 할지 정하고, 모임이 끝난 뒤에는 여러 지출을 다시 정산해야 합니다.

**모임 구축해주겠어**는 이 과정을 하나의 서비스에서 관리합니다.

- 후보별 선호도를 반영한 일정 조율
- 모임원이 함께 참여하는 콘텐츠 결정
- 지출 참여자를 기준으로 한 통합 정산
- 초대 링크를 통한 모임 참여
- 투표 종료 및 정산 완료 알림

---
## 🔄 Kotlin 마이그레이션

2차 프로젝트의 Java 백엔드를 Kotlin으로 전환한 3차 프로젝트입니다. 기존 서비스의 API와 도메인 동작을 유지하면서 운영 코드와 테스트를 Kotlin으로 옮기고, 전환 과정의 회귀 검증과 개발 도구를 보강했습니다.

### 주요 변경 사항

| 구분 | 반영 내용 |
| --- | --- |
| 전환 범위 | 회원·인증, 모임·초대·모임원, 일정 투표, 콘텐츠 투표, 지출, 정산, 알림 및 공통 응답·예외·보안 설정을 Kotlin으로 전환 |
| 소스 구조 | 운영 코드는 `backend/src/main/kotlin`, 일반 테스트는 `backend/src/test/kotlin`으로 통합 |
| Kotlin 빌드 | Kotlin JVM·Spring·JPA 플러그인, Kotlin 리플렉션 및 Jackson Kotlin 모듈 적용 |
| JPA 호환성 | JPA 기본 생성자 생성과 엔티티 `allOpen` 설정 적용, 저장·재조회 및 버전 필드 동작 검증 |
| 요청 검증 | DTO의 nullable 타입과 `@field:` 검증 대상 명시, 컬렉션 원소 검증을 위한 JVM 타입 애노테이션 출력 설정 적용 |
| 테스트 | Kotlin 테스트 및 Mockito Kotlin을 사용하도록 전환하고, 지출·정산 금액 정합성·기존 데이터 호환성·동시성 검증 보강 |
| 코드 품질·CI | ktlint 공통 규칙과 PR 형식 검사, GitHub Actions 백엔드 테스트 및 결과 보고서 업로드 추가 |
| 관측·부하 테스트 | Actuator·Micrometer 기반 API별 SQL 실행 횟수 수집, Prometheus·Grafana 구성 및 k6 읽기 API 부하 테스트 스크립트 추가 |

### 마이그레이션 회귀 검증

- **계산 결과 비교**: 전환 전 Java 코드에서 생성한 기준값(`golden-results.txt`)과 Kotlin의 지출·정산 계산 및 응답 결과를 비교합니다.
- **기존 데이터 호환성**: Java 엔티티로 MySQL에 저장한 SQL fixture를 Kotlin 엔티티로 읽어 금액, 컬렉션 순서, 정산 상태와 계좌 스냅샷을 검증합니다.
- **API·동시성 검증**: 지출 등록부터 정산 미리보기·계좌 등록·확정·조회·커밋 후 알림 저장까지의 흐름과 정산 확정 동시 요청 등을 테스트합니다.

기준 데이터 생성용 Java 파일은 `backend/src/test/fixtures`에 별도로 보관하며, 일반 테스트 실행에는 포함하지 않습니다.

설계와 변환 규칙은 [Kotlin 마이그레이션 컨벤션](backend/docs/kotlin-migration.md), Java 저장 데이터 검증 절차는 [fixture 안내](backend/src/test/fixtures/settlement/README.md)를 참고합니다.

---
## 🛠 Tech Stack

### Backend

| | Technology |
| --- | --- |
| Language | Kotlin 2.3.21 (JDK 25) |
| Framework | Spring Boot 4.1.1 |
| ORM | Spring Data JPA |
| Security | Spring Security, JWT (JJWT 0.13.0) |
| Database | MySQL |
| DB Migration | Flyway |
| API Docs | SpringDoc OpenAPI / Swagger |
| Build | Gradle |
| Test | JUnit 5, Kotlin Test, Mockito Kotlin, Testcontainers (MySQL) |
| Code Style | ktlint Gradle Plugin 14.2.0 / ktlint 1.7.1 |
| Monitoring | Spring Boot Actuator, Micrometer, Prometheus, Grafana |
| Load Test | k6 |

### Frontend

| | Technology |
| --- | --- |
| Framework | Next.js 16 |
| UI | React 19 |
| Language | TypeScript |
| Styling | Tailwind CSS 4 |
| Deployment | Vercel |

### Infra & Collaboration

| | Technology |
| --- | --- |
| Backend Deployment | Railway |
| Database | MySQL |
| Local DB | Docker Compose |
| CI | GitHub Actions (ktlint, Backend tests) |

---
## Getting Started
### Backend

JDK 25와 Docker가 필요합니다. Gradle은 저장소에 포함된 Wrapper를 사용합니다.

#### 1. 환경변수 설정

`backend/.env.example`을 복사해 `backend/.env`를 만듭니다.

```bash
cd backend
cp .env.example .env
```

`.env`에서 MySQL 접속 정보와 `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`을 설정합니다. 두 JWT 시크릿은 서로 다른 랜덤 값으로, 각각 UTF-8 기준 최소 32바이트로 설정합니다.

```env
SPRING_PROFILES_ACTIVE=local
MYSQLHOST=localhost
MYSQLPORT=3306
MYSQLDATABASE=team02
MYSQLUSER=team02_user
MYSQLPASSWORD=your_password
MYSQL_ROOT_PASSWORD=your_root_password
JWT_ACCESS_SECRET=your_jwt_access_secret
JWT_REFRESH_SECRET=your_jwt_refresh_secret
```

#### 2. 실행 프로필 설정
프로필을 지정하지 않으면 기본으로 `local` 프로필이 적용됩니다. 운영 환경에서는 배포 환경변수에 `SPRING_PROFILES_ACTIVE=prod`를 추가합니다.

```env
SPRING_PROFILES_ACTIVE=prod
```

#### 3. DB 스키마 관리 (Flyway)
DB 테이블은 처음 실행할 때 Flyway가 빈 DB에 자동으로 생성합니다. 스키마를 변경할 때는 `backend/src/main/resources/db/migration`에 새 버전의 SQL 파일을 추가합니다. 

#### 4. MySQL 및 모니터링 실행

```bash
docker compose up -d
```

MySQL, Prometheus, Grafana를 함께 실행합니다. MySQL만 필요하면 `docker compose up -d mysql`을 사용합니다.

#### 5. 애플리케이션 실행

```bash
./gradlew bootRun
```

서버 실행 후 API 문서는 [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)에서 확인할 수 있습니다.

#### 6. Kotlin 마이그레이션 코드 스타일

백엔드의 Kotlin 형식 검사는 `backend`에서 `./gradlew ktlintCheck`로 실행합니다.
Windows PowerShell에서는 `.\gradlew.bat ktlintCheck`를 사용합니다.
팀원 초기 설정, 자동 수정 명령, Kotlin 빌드 설정과의 병합 방법은 [ktlint 사용 안내](backend/docs/ktlint.md)를 참고합니다.

#### 7. 테스트 실행

`backend`에서 다음 명령을 실행합니다.

```bash
./gradlew ktlintCheck test
```

DB 통합 테스트는 Testcontainers로 별도 MySQL 컨테이너를 사용하므로 Docker가 실행 중이어야 합니다. 테스트용 DB 접속 정보와 JWT 시크릿은 공통 테스트 설정에서 주입합니다.

PR에서는 GitHub Actions가 형식 검사와 테스트를 각각 실행합니다. 테스트 보고서는 `backend/build/reports/tests/test/index.html`과 CI의 `backend-test-reports` 아티팩트에서 확인할 수 있습니다. 자세한 구성은 [백엔드 테스트 CI 안내](backend/docs/backend-test-ci.md)를 참고합니다.

#### 8. 모니터링 및 부하 테스트

애플리케이션과 Docker Compose 서비스가 실행된 상태에서 확인합니다.

| 항목 | 로컬 주소 / 설명 |
| --- | --- |
| Prometheus | [http://localhost:9090](http://localhost:9090) |
| Grafana | [http://localhost:3001](http://localhost:3001), 기본 계정 `admin` / `admin` |
| 메트릭 수집 | 관리 포트 `8081`의 `/actuator/prometheus`를 Prometheus가 수집 |
| API 쿼리 대시보드 | API별 요청당 SQL 실행 횟수와 SQL 종류별 실행 횟수 확인 |

Grafana 계정은 `backend/.env`의 `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD`로 설정할 수 있습니다. 애플리케이션의 관리 포트를 변경하면 `backend/monitoring/prometheus.yml`의 수집 대상 포트도 함께 수정합니다.

k6가 설치되어 있다면 `backend`에서 읽기 API 부하 테스트를 실행할 수 있습니다. 스크립트는 테스트용 회원·모임·지출 데이터를 생성합니다.

```bash
k6 run -e BASE_URL=http://localhost:8080 -e USERS=15 -e EXPENSES=50 -e VUS=10 -e DURATION=60s load/read-heavy.js
```



### Frontend

#### 1. 의존성 설치

```bash
cd frontend
npm install
```

#### 2. 환경변수 설정

로컬 프론트 환경변수는 `frontend/.env.local`에 둡니다. 

```env
# frontend/.env.local
BACKEND_URL=http://localhost:8080
```

#### 3. 개발 서버 실행

```bash
npm run dev
```

브라우저에서 [http://localhost:3000](http://localhost:3000)을 엽니다.


---

## Team

**Programmers Backend Devcourse 12기 14회차 · Team 02**

| GitHub |
| --- |
| [@sowon11](https://github.com/sowon11) |
| [@jihoo414-tech](https://github.com/jihoo414-tech) |
| [@jinni1](https://github.com/jinni1) |
| [@jjjonghyeon](https://github.com/jjjonghyeon) |
| [@minj00-kim](https://github.com/minj00-kim) |
