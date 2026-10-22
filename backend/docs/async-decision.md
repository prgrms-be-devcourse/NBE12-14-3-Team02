# 비동기 처리 도입 판단

이 문서는 비동기·가상 스레드·코루틴 도입을 검토하거나 구현하는 작업에서만 읽습니다. 아래 내용은 검토 기준과 도입 시 예시이며, 도입이 확정되었다는 의미가 아닙니다. Kotlin 변환만 요청된 경우에는 적용하지 않습니다. 마이그레이션 규칙은 [kotlin-migration.md](kotlin-migration.md)를 따릅니다.

## 현재 상태

- `@EnableScheduling`과 스케줄러 2개: `ContentPollCloseScheduler`, `SchedulePollCloseScheduler`
- `@TransactionalEventListener(AFTER_COMMIT)` 리스너 3개: `SettlementClosedEventListener`, `ContentPollClosedEventListener`, `SchedulePollClosedEventListener`. 커밋 후에 **이벤트를 발행한 스레드에서 동기로** 실행됩니다. 요청 처리 중 발행한 경우 알림 저장 시간이 API 응답 시간에 포함됩니다.
- 정산·콘텐츠 리스너는 별도 writer의 `REQUIRES_NEW` 트랜잭션에서 저장하고, 일정 리스너는 리스너 메서드 자체에 `REQUIRES_NEW`가 붙어 있습니다.
- 외부 HTTP 호출은 없습니다.

## 도입 여부를 먼저 판단

비동기·가상 스레드·코루틴은 Kotlin 전환의 필수 작업이 아닙니다. 먼저 응답 시간 중 알림 저장이 차지하는 비중과 DB 커넥션 대기를 측정합니다. 비동기로 분리할 근거가 있으면 별도 PR로 진행합니다.

- 응답 전에 알림 저장이 완료되어야 하는지, 어느 정도 지연을 허용하는지 정합니다.
- 서버 종료나 실행 실패로 인한 알림 유실을 허용하는지 정합니다. 허용하지 않으면 인메모리 `@Async`만으로 해결하지 않고 outbox 등 영속적인 작업 기록과 재처리를 검토합니다.
- 요청 지연뿐 아니라 알림 완료 지연·실패와 DB 부하도 함께 비교합니다.

## `@Async`를 선택한 경우

아래는 알림 저장의 지연·유실 허용 범위를 정한 뒤 적용하는 예시입니다.

```kotlin
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
fun onClosed(event: SettlementClosedEvent) {
    try {
        notificationWriter.save(event.meetingId)
    } catch (e: RuntimeException) {
        log.error("정산 확정 알림 저장 실패: meetingId={}, settlementId={}", event.meetingId, event.settlementId, e)
    }
}
```

- 실패 로그와 관측 방법을 마련합니다. 반환값이 없는 `@Async` 메서드의 예외는 호출자에게 전달되지 않습니다.
- DB 쓰기는 별도 Spring 빈인 writer의 `@Transactional(propagation = REQUIRES_NEW)` 메서드를 호출합니다. 일정 리스너에도 이 예시를 적용하려면 writer 분리가 선행되어야 합니다.
- 반드시 실행되어야 하는 후속 작업은 영속적인 작업 기록·재처리 보장 없이 인메모리 비동기로 분리하지 않습니다.

## 실행기 선택

`@Async`를 도입할 때 `@EnableAsync`와 실행기를 함께 설정합니다. 전용 실행기로 알림 작업의 동시 실행 수·대기 작업 수·포화 시 처리 정책을 정합니다. Java 25 사용 자체는 가상 스레드를 켤 근거가 아닙니다.

가상 스레드를 선택한다면 아래 전역 설정의 영향 범위를 확인합니다.

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

- 이 설정은 `@Async`뿐 아니라 Tomcat 요청 처리에도 적용됩니다. 전역 변경이므로 별도 PR로 올리고 기존 테스트 전체를 돌립니다.
- 자동 구성 실행기는 가상 스레드를 사용하지만, 사용자 정의 실행기가 있으면 실제 선택되는 실행기를 확인합니다. [Spring Boot 실행기 문서](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html)를 참고합니다.
- DB 커넥션 풀은 동시에 커넥션을 사용하는 수를 제한할 뿐, 대기 작업의 수까지 제한하지 않습니다. 실제 풀 크기와 요청 처리의 커넥션 사용량을 확인하고, 작업 유입량·동시 실행 수·대기를 별도로 제한합니다.

## 테스트

비동기로 바꾸면 "요청 직후 알림이 저장되었다"를 바로 검증하는 테스트가 실패하거나 간헐적으로 실패합니다(`SettlementClosedEventTest`, `ContentPollClosedEventTest`, `NotificationIntegrationTest` 등). Awaitility로 저장될 때까지 기다리도록 바꿉니다.

대기에는 제한 시간을 두고, 롤백 시 알림 미생성·작업 실패·실행기 포화 시 동작도 검증합니다. 이 테스트 변경은 비동기 PR에 포함합니다.

## 코루틴

`suspend` 함수에 `@Transactional`을 붙이지 않습니다. JPA 트랜잭션과 영속성 컨텍스트는 스레드에 묶여 있는데, 코루틴은 중단 후 다른 스레드에서 재개될 수 있습니다.

현재 프로젝트에서는 독립적인 블로킹 작업을 병렬 실행할 필요가 측정으로 확인된 경우에만 코루틴을 검토합니다. 각 작업은 트랜잭션이 시작되고 끝나는 일반(블로킹) 함수 하나를 별도 Spring 빈을 통해 호출합니다. 동시 실행 수는 다른 요청의 커넥션 사용량을 고려해 정합니다.

```kotlin
// 개념 예시: 한 배치 안의 동시 실행 수를 제한합니다.
// 실제 도입 시 배치 중복 실행과 전체 작업 수 제한, 개별 실패 처리도 필요합니다.
// closeOneIfExpired는 별도 Spring 빈의 @Transactional 메서드입니다.
val dispatcher = Dispatchers.IO.limitedParallelism(4)
coroutineScope {
    expiredPollIds.forEach { pollId ->
        launch(dispatcher) { contentPollCloseService.closeOneIfExpired(pollId) }
    }
}
```

도입할 때는 `kotlinx-coroutines-core` 의존성 추가를 별도 PR로 올립니다.

## 성능 개선 PR

- 개선 전후 수치와 측정 방법을 PR에 적습니다(예: 정산 확정 API 응답 시간 p50/p95).
- 측정하지 않은 "성능 개선"은 PR 설명에 쓰지 않습니다.

## PR 체크리스트

- [ ] 병목 측정에 근거해 도입 여부를 판단했다
- [ ] `@Async` 도입 시 `@EnableAsync`와 실행기를 함께 설정했다
- [ ] 지연·유실 허용 범위, 작업량 제한, 실패 처리와 관측 방법을 정했다
- [ ] 비동기 완료 대기·롤백·실패·실행기 포화 시 동작을 검증했다
- [ ] 성능 개선을 주장하는 경우 전후 측정값과 측정 방법을 첨부했다

## 참고 문서

- [Spring Boot: Task Execution and Scheduling](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-guide.html)
