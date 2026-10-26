# Java 영속 데이터 호환성 fixture

`src/test/resources/settlement/java-persisted-fixture.sql`은 엔티티 전환 전 커밋
`1134c4d`의 Java 엔티티와 저장소로 실제 MySQL에 저장한 행을 내보낸 파일이다.
현재 Kotlin 코드가 이 SQL을 직접 읽으며, 테스트 준비 단계에서 현재 엔티티로 데이터를 다시 생성하지 않는다.

생성기 `JavaSettlementFixtureGeneratorTest.java`는 일반 테스트 소스 밖에 둔다.
기준값 생성 작업이 정상 테스트 실행 중 섞이거나 Kotlin 코드로 기준값을 덮어쓰는 일을 방지한다.

포함하는 사례:

- 개인별 부담액과 분담 옵션이 없는 기존 지출: 10원을 ID 5·17·41에 4·3·3원 배분.
- 0원 부담자를 포함하고 Int 범위를 넘는 지정 지출: 3,000,000,001원.
- 100원 단위의 균등 지출: 3,300·3,300·3,400원과 차액 담당자 41.
- 등록 요청 번호와 지출 ID의 연결, 영수증 키, nullable 메모·옵션.
- 확정 정산의 개인별 잔액·송금 순서·계좌 스냅샷과 OPEN 정산의 빈 컬렉션·null 확정 정보.

실행 시각만 고정했다. 금액, 문자열 처리, 컬렉션 순서, 분담 옵션과 버전은 Java JPA가 기록한 값이다.
fixture의 ID는 900000부터 시작하고, 모임 ID는 890001·890002를 사용한다.
호환성 테스트는 `@DataJpaTest` 트랜잭션 안에서 SQL을 로드하고 종료 시 롤백한다.

## 재현

Java 25와 Docker가 필요하다. 저장소 루트에서 아래 명령을 실행한다.
생성 경로는 비어 있는 새 경로를 사용한다.

```bash
task_repo=$(git rev-parse --show-toplevel)
git worktree add --detach /tmp/settlement-java-baseline 1134c4d
cp "$task_repo/backend/src/test/fixtures/settlement/JavaSettlementFixtureGeneratorTest.java" \
  /tmp/settlement-java-baseline/backend/src/test/java/com/prgms/backend/domain/settlement/
cd /tmp/settlement-java-baseline/backend
SETTLEMENT_FIXTURE_OUT=/tmp/java-persisted-fixture.sql \
  ./gradlew test --tests '*JavaSettlementFixtureGeneratorTest'
diff -u "$task_repo/backend/src/test/resources/settlement/java-persisted-fixture.sql" \
  /tmp/java-persisted-fixture.sql
```

정상적인 언어 전환에서는 fixture를 재생성하지 않는다.
동작 규칙을 바꾸는 경우에는 기준 커밋·생성 절차·호환성 테스트의 수기 기대값을 함께 검토한다.
