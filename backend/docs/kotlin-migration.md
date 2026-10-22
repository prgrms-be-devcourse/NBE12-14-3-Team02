# Kotlin 마이그레이션 컨벤션

Java 백엔드를 Kotlin으로 옮길 때 지키는 규칙입니다. 코드 형식은 [ktlint.md](ktlint.md)가 검사하고, 이 문서는 설계와 문법 선택을 다룹니다.

## 필수 규칙 10줄 요약

1. 변환 PR은 기존 동작·API 계약·테스트의 검증 의도를 유지하고, 로직 개선은 별도 PR로 진행합니다.
2. 의존받는 코드부터 도메인별로 옮기고, 첫 도메인에서 Java 혼용 빌드·JPA 재조회·API 응답을 검증합니다.
3. DTO는 `data class`와 `val`만 쓰고, 요청 검증 애노테이션에는 `@field:`를 명시합니다.
4. 요청 DTO의 기존 Java 참조 타입은 nullable로 옮기고 원시 타입은 기존 기본값·null 처리 계약을 유지하며, 필수 참조 값의 non-null 전환은 별도 PR로 진행합니다.
5. 응답 DTO의 기존 정적 팩토리는 companion object의 `from(...)`으로 옮깁니다.
6. 엔티티는 일반 `class`, ID는 `var id: Long = 0`, 공개 매핑 프로퍼티는 `var`와 `protected set`으로 선언합니다.
7. 기본은 `val`, 의존성은 `private val` 생성자 주입으로 쓰고, 엔티티 상태는 도메인 메서드로 변경합니다.
8. 컬렉션은 내부 가변·외부 복사본으로 분리하고, 기존 `Set`·`Map`의 의미와 JPQL 필드 이름을 유지합니다.
9. null은 `?:`·`?.`로 처리하고, `!!` 예외에는 사유를 남기며 직접 선언한 조회 메서드는 `Optional` 대신 nullable을 반환합니다.
10. 작업 범위를 확인하며 `ktlintFormat`을 먼저 실행한 뒤 `ktlintCheck test`를 통과하고, 불필요해진 Java 호환 애노테이션을 제거합니다.

비동기·가상 스레드·코루틴의 도입 판단 또는 구현을 요청받은 경우에만 [async-decision.md](async-decision.md)를 읽습니다. Kotlin 변환 작업에서는 기존 동기 동작을 유지합니다.

## 평가 항목과 대응

| 평가 항목 | 이 문서 | PR에서 드러나야 할 것 |
| --- | --- | --- |
| Nullable / Non-nullable 명시 및 적용 | [2. Null 안정성](#2-null-안정성) | `!!` 없음, `Optional` 제거, DB 제약과 타입 일치 |
| 데이터 클래스 | [3. 클래스 종류별 규칙](#3-클래스-종류별-규칙) | `record` → `data class`, 오버로드 생성자 → 기본 인자 |
| Kotlin 함수 및 확장 함수 적용 | [4. 함수형 문법](#4-함수형-문법), [5. 확장 함수](#5-확장-함수) | stream → 컬렉션 함수, 반복 조회 로직의 확장 함수 |
| 성능 개선 리팩토링 (비동기 처리) | [별도 도입 판단 문서](async-decision.md) | 병목 측정, 도입 판단 근거, 개선 전후 측정값 |

## 1. 진행 방식

### 옮기는 순서

도메인 하나씩, **다른 코드가 의존하는 쪽부터** 옮기는 것을 기본으로 합니다. 아래 순서는 고정 규칙이 아니며, 실제 의존관계와 Java 호출자 범위에 따라 조정합니다.

```
global(exception, entity) → 도메인별 entity → dto → repository → service → controller → 테스트
```

현재 혼용 빌드에서는 Kotlin 컴파일 시 Java Lombok이 생성할 getter·생성자를 그대로 사용할 수 없습니다. 의존받는 계층부터 옮겨 이 문제를 줄이되, Java가 Kotlin을 호출하는 쪽도 접근자·생성자·정적 메서드의 호환성을 확인합니다.

다른 도메인의 Java 엔티티를 참조해서 막히면 그 엔티티를 먼저 옮깁니다. 그래도 풀리지 않을 때만 Kotlin Lombok 플러그인을 추가합니다([7. 빌드 설정 선행 작업](#7-빌드-설정-선행-작업)).

### 첫 도메인에서 검증할 것

의존 범위가 작고 기존 테스트가 있는 도메인 하나를 먼저 전환합니다. 다음을 확인한 뒤 같은 방식을 다른 도메인에 적용합니다.

- 남아 있는 Java 운영 코드와 테스트가 함께 컴파일되는지
- 엔티티 저장 후 영속성 컨텍스트를 비우고 재조회했을 때 매핑·컬렉션·감사 필드가 정상인지, 지연 로딩을 사용하는 연관관계가 정상인지
- no-arg 생성자는 기본적으로 필드 초기화 식을 실행하지 않으므로, 재조회 후 컬렉션과 비영속 필드 등 초기값에 의존하는 상태가 null로 남지 않고 도메인 메서드가 정상 동작하는지
- `Expense`처럼 `@Version`이 없는 엔티티의 `id == 0L` 신규 저장과, `Settlement`처럼 nullable `@Version`이 있는 엔티티의 신규 저장·재조회·수정 시 버전 증가가 정상인지
- 기존 API의 정상 응답과 잘못된 입력에 대한 상태 코드·응답 본문이 유지되는지

첫 PR에는 실제로 발생한 호환성 문제, 해결 방법, 검증 결과를 기록합니다.

### 파일 위치

`src/main/kotlin`, `src/test/kotlin` 아래에 기존과 같은 패키지로 둡니다.

### PR 단위

- **변환과 동작 변경을 섞지 않습니다.** 로직 개선, 패키지 이름 정리(`ENUM` → `enums` 등), 비동기 전환은 별도 PR로 올립니다.
- IntelliJ의 **Convert Java File to Kotlin File**로 1차 변환한 뒤 이 문서 기준으로 정리합니다. 변환기가 만든 `!!`, `lateinit`, 불필요한 `?`를 그대로 두지 않습니다.
- 기존 테스트의 검증 의도와 기대 결과를 유지합니다. 접근자·생성자 등 호출 문법 변경은 같은 PR에서 허용하지만, 테스트를 통과시키기 위해 assertion을 삭제하거나 기대 결과를 바꾸지 않습니다. API 계약 변경이 필요하면 별도 변경으로 다룹니다.
- 머지 전 `./gradlew ktlintFormat`을 먼저 실행하고 변경 범위가 작업 대상에 한정되는지 확인한 뒤 `./gradlew ktlintCheck test`를 통과합니다. 다른 팀원 파일의 형식 변경은 포함하지 않습니다.

### Java 호출 호환성

변환할 클래스의 Java 호출자를 먼저 찾고, 같은 PR에서 호출부를 수정할지 기존 호출 형태를 유지할지 정합니다. 임시 호환 메서드와 Java 호출 유지만을 위해 붙인 `@JvmStatic`·`@JvmOverloads`는 Java 호출자가 없어지면 제거합니다. 외부 공개 API 등 별도 호환성 요구가 있으면 유지 사유를 PR에 적습니다.

| 변경 | 혼용 기간의 처리 |
| --- | --- |
| `record` 접근자 → Kotlin 프로퍼티 | 일반 프로퍼티는 Java에서 `getTitle()`처럼 호출합니다. 기존 `title()` 호출부를 수정하거나 호환 메서드를 유지합니다. Boolean 및 `is` 접두사 프로퍼티는 실제 생성된 접근자 이름도 확인합니다. |
| 오버로드 생성자 → 기본 인자 | 기본 인자만으로 Java용 오버로드가 유지되지는 않습니다. 필요한 경우 `@JvmOverloads` 또는 보조 생성자로 기존 시그니처를 유지합니다. |
| 정적 팩토리 → companion object | 기존 `Expense.register(...)` 호출을 유지하려면 companion 함수에 `@JvmStatic`을 붙입니다. |
| 응답 DTO의 정적 `from(...)` → companion object | `companion object`의 `from(...)`으로 옮깁니다. Java의 `ExpenseResponse.from(...)` 호출을 유지하려면 `@JvmStatic`을 붙입니다. |
| 컬렉션 getter → 복사 함수 | 기존 `getParticipantIds()`를 유지하거나 Java 호출부를 함께 수정합니다. |
| `Optional<T>` → `T?` | Java 호출부의 `orElseThrow()` 등을 함께 수정합니다. |

호출 방식은 [Kotlin의 Java 상호운용 문서](https://kotlinlang.org/docs/java-to-kotlin-interop.html)를 기준으로 확인합니다.

## 2. Null 안정성

### 원칙

- `?`는 "값이 없을 수 있다"는 도메인 사실을 표현합니다. DB 컬럼이 `nullable = false`이면 non-null, null을 허용하는 컬럼이면 `?` 타입입니다.
- **`!!`는 쓰지 않습니다.** 불가피하면 그 줄에 이유를 주석으로 남깁니다. 대신 다음을 씁니다.

| 상황 | 사용 |
| --- | --- |
| 없으면 기본값 / 예외 | `?:` |
| 인자 검증 (`IllegalArgumentException`) | `requireNotNull(x) { "..." }` |
| 상태 검증 (`IllegalStateException`) | `checkNotNull(x) { "..." }` |
| 있을 때만 실행 | `?.let { }` |

- `lateinit`은 JPA가 채우는 필수 연관관계·감사 필드와 테스트 설정(`@Autowired` 등)에만 제한적으로 씁니다. 생성 시 필요한 연관관계는 가능한 한 생성자로 받고, 실제로 없을 수 있는 값은 nullable로 표현합니다. 운영 코드의 의존성은 `private val` 생성자로 주입합니다.
- 안전 호출은 `?.`를 쓰고, 연속된 안전 호출은 한 줄에 하나씩 나눕니다.

```kotlin
val message = e.bindingResult.fieldError
    ?.defaultMessage
    ?: "잘못된 요청입니다."
```

- **아직 Java인 코드의 반환값은 타입을 명시합니다.** 그러지 않으면 nullable 여부를 모르는 플랫폼 타입(`User!`)이 그대로 퍼집니다.

```kotlin
val user = userFinder.find(id)          // X: 플랫폼 타입
val user: User? = userFinder.find(id)   // O
```

### `Optional` 제거

```kotlin
// Java: expenseRepository.findById(id).orElseThrow(...)
expenseRepository.findByIdOrNull(id) ?: throw ExpenseNotFoundException(id)
```

- `findByIdOrNull`은 `org.springframework.data.repository.findByIdOrNull`을 import합니다.
- 직접 선언한 조회 메서드는 `Optional<T>` 대신 `T?`를 반환합니다.

### 엔티티 id

이 프로젝트의 DB 자동 생성 식별자는 **`var id: Long = 0` + `protected set`으로 통일**합니다. `0`은 저장 전 상태를 뜻하며 유효한 식별자로 외부에 전달하지 않습니다. `Long?` 방식과 혼용하지 않습니다.

```kotlin
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
var id: Long = 0
    protected set
```

`val`로 선언하면 `private final` 필드가 되고, JPA가 이 final 필드를 리플렉션으로 채우게 됩니다. JDK 26부터 리플렉션으로 final 필드를 변경하면 경고가 나오고 이후 차단될 예정이므로([JEP 500](https://openjdk.org/jeps/500)) `var`로 선언합니다. ID는 애플리케이션에서 변경하지 않고 JPA가 필드 접근으로 채웁니다. 첫 엔티티 전환 시 현재 Hibernate 설정에서 신규 엔티티 판별, 저장 후 ID 할당, 재조회를 확인합니다. 응답에는 저장된 엔티티의 ID를 사용하며, 저장 전 객체를 변환할 수 있는 경계에서는 `check(id != 0L)`로 확인합니다.

`Settlement`의 기존 `@Version Long version`은 Kotlin에서도 `var version: Long? = null`과 `protected set`으로 유지합니다. Spring Data JPA는 nullable 버전 프로퍼티가 있으면 ID보다 버전의 null 여부를 먼저 사용해 신규 엔티티를 판별하므로, `0`으로 초기화하거나 non-null로 바꾸지 않습니다. [Spring Data JPA 신규 엔티티 판별](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html)을 참고합니다.

### 요청 DTO

**변환 PR에서는 기존 API 계약을 유지합니다.** 요청 DTO의 기존 Java 참조 타입(`String`, `BigDecimal`, `Long`, `List` 등)은 필수 값이어도 `?` 타입으로 두고, Java에서 `@NotNull`이던 필드는 `@field:NotNull`을 유지합니다. 기존 원시 타입(`boolean`, `int`, `long` 등)은 일괄 nullable 전환 대상에서 제외하고, 기본값·필드 누락·명시적 null 처리와 Java 호출 시그니처를 확인합니다. 예를 들어 `boolean randomRemainder`는 `val randomRemainder: Boolean = false`로 옮기되 누락과 명시적 null 요청의 응답이 기존과 같은지 검증합니다.

필수 필드를 non-null 타입으로 바꾸면 필드가 빠진 요청이 Bean Validation 이전의 Jackson 역직렬화 단계에서 실패합니다. 그러면 400 응답의 메시지가 Validation 메시지에서 Jackson 내부 메시지로 바뀌고, 현재 `GlobalExceptionHandler`는 이 메시지를 그대로 응답합니다. 기존 테스트는 이 경우의 메시지를 검증하지 않아 변경이 드러나지 않습니다.

- 검증을 통과한 값을 non-null로 꺼낼 때는 `!!` 대신 `checkNotNull`을 쓰고, 꺼내는 위치를 요청 → 도메인 변환 지점 한 곳으로 모읍니다.
- 필수 필드의 non-null 전환은 별도 PR에서 진행합니다. 이 PR에서는 다음을 함께 합니다.
  - `HttpMessageNotReadableException` 처리가 내부 메시지 대신 고정 메시지를 응답하도록 수정
  - 필수 필드 누락·명시적 null·null 원소 요청에 대한 기존 API 테스트를 실행하고, 역직렬화 단계 변경에 맞춰 검증 보완
  - non-null 타입과 중복되는 `@field:NotNull` 제거
- 요청 DTO 프로퍼티의 검증 애노테이션에는 **`@field:NotBlank`, `@field:Size`, `@field:Positive`처럼 use-site target을 반드시 명시**합니다. 중첩 객체 검증도 `@field:Valid`로 지정합니다.
- 현재 빌드의 `-Xannotation-default-target=param-property`에서는 target 생략 시에도 필드에 적용될 수 있습니다. 따라서 생략하면 항상 검증이 실패하는 것은 아닙니다. 팀 규칙은 컴파일러 기본 동작에 기대지 않고 검증 대상을 명시하는 것입니다. [Kotlin 애노테이션 문서](https://kotlinlang.org/docs/annotations.html#annotation-use-site-targets)를 참고합니다.

#### 컬렉션 원소 검증

목록 자체의 제약과 원소의 제약을 구분합니다. 기존 `List<@NotNull @Valid Participant>`는 목록의 `@field:NotEmpty`·`@field:Size`, 중첩 객체 검증의 `@field:Valid`, 원소의 null 금지 제약을 각각 유지합니다. `@field:Valid`만으로 null 원소가 금지되지는 않습니다. 원소 제약은 프로퍼티의 `@field:` 대신 타입 인자에 붙이는 애노테이션이므로 아래처럼 작성합니다.

```kotlin
@field:NotEmpty
@field:Size(max = 100)
@field:Valid
val participants: List<@NotNull Participant?>?,
```

변환 PR에서는 null 원소가 기존처럼 Bean Validation에서 거절되도록 원소 타입도 nullable로 둡니다. **`-Xemit-jvm-type-annotations`는 필수 빌드 설정입니다.** 이 옵션이 없으면 타입 인자에 붙인 `@NotNull`이 JVM 바이트코드에 기록되지 않아 검증기가 null 원소를 거절하지 못합니다. `-Xannotation-default-target=param-property`와 `@field:`는 프로퍼티 애노테이션의 적용 위치를 정할 뿐, 타입 인자 애노테이션의 출력을 대신하지 않습니다. 현재 `build.gradle`의 `kotlin.compilerOptions.freeCompilerArgs`에 출력 옵션을 적용합니다. [Kotlin JVM 타입 애노테이션 문서](https://kotlinlang.org/docs/whatsnew14.html#type-annotations-in-the-jvm-bytecode)를 참고합니다.

목록 누락·명시적 null·빈 목록·`[null]`·필드가 잘못된 원소에 대한 API 테스트를 변환 PR에서 실행하고, 없는 경우 추가합니다. 이 검증을 향후 non-null 전환 PR로 미루지 않습니다. [Hibernate Validator 원소 제약 문서](https://docs.hibernate.org/validator/8.0/reference/en-US/html_single/#section-container-element-constraints)를 참고합니다.

### Spring API

Spring API에는 null 가능 여부가 표시되어 있어서 Kotlin 타입으로 그대로 드러납니다. 체크 후 다시 호출하던 Java 코드는 안전 호출로 줄입니다.

```kotlin
val message = e.bindingResult.fieldError
    ?.defaultMessage
    ?: "잘못된 요청입니다."
```

## 3. 클래스 종류별 규칙

### `val` / `var`와 컬렉션

- 기본은 `val`이며 재할당이 필요한 상태에만 `var`를 씁니다. `val` 자체가 객체 내부까지 불변으로 만들지는 않습니다. 단, JPA가 채우는 엔티티 필드는 예외입니다([엔티티](#엔티티)).
- 의존성은 `private val` 생성자 주입으로 통일합니다.
- 목록 컬렉션은 내부에서 `MutableList`로 관리하고 외부에는 `List`로 노출합니다. 내부 컬렉션을 그대로 반환하지 않고 `toList()`로 복사해 외부 변경을 차단합니다.
- 기존 `Set`의 중복 방지, `Map`의 키 기반 조회 의미는 유지합니다. 예를 들어 `Expense.participantIds`와 `participantAmounts`는 각각 `MutableSet`/`MutableMap`으로 관리하고 외부에는 복사한 `Set`/`Map`을 제공합니다. 컬렉션 종류나 JPA 매핑 변경이 필요하면 별도 리팩토링으로 다룹니다.

### 엔티티

- **`data class`를 쓰지 않습니다.** 주 생성자 프로퍼티로 자동 생성되는 `equals`/`hashCode`/`toString`에 연관관계나 변경 가능한 상태가 포함되면 지연 로딩·순환 참조·해시값 변경 문제가 생길 수 있습니다.
- 일반 `class`의 주 생성자로 필수 값을 받습니다. 주 생성자 파라미터는 프로퍼티(`val`/`var`)로 선언하지 않고, 클래스 본문의 프로퍼티를 초기화하는 데만 씁니다.
- **매핑 필드는 `val` 대신 `var` + `protected set`으로 선언합니다.** `val`은 final 필드가 되어 JPA가 리플렉션으로 값을 채울 때 문제가 됩니다([엔티티 id](#엔티티-id) 참고). 값이 바뀌지 않는 필드는 변경 메서드를 만들지 않는 것으로 표현하고, 도메인 메서드로만 상태를 바꿉니다.
- `private set`은 쓸 수 없습니다. `allOpen`이 엔티티의 프로퍼티까지 open으로 만들기 때문에 `Private setters for open properties are prohibited` 컴파일 에러가 납니다.
- 컬렉션 필드도 Hibernate가 조회 시 자체 컬렉션으로 교체하므로 `private var`로 선언합니다.
- 기본 생성자·프록시를 위한 `open` 처리는 컴파일러 플러그인에 맡깁니다. 현재 Kotlin 버전에서는 `kotlin-jpa`의 기본 생성자 생성과 JPA용 `allOpen` 설정이 필요하며, `kotlin-spring`만으로 엔티티가 열리지는 않습니다([7. 빌드 설정 선행 작업](#7-빌드-설정-선행-작업)).
- 식별자는 [엔티티 id 규칙](#엔티티-id)에 따라 `var id: Long = 0` + `protected set`을 씁니다.
- `equals`/`hashCode`는 기본 구현을 유지합니다. 도메인상 동일성 비교가 필요하면 ID 기반으로 구현하되, 서로 다른 미저장 엔티티(`id == 0L`)를 같다고 판단하지 않고 프록시 비교와 저장 전후 해시 컬렉션 동작을 검증합니다.
- `toString`을 재정의할 때 연관관계와 컬렉션을 출력하지 않습니다.
- Lombok 빌더는 생성자와 기본 인자로 대체합니다. 기존 정적 팩토리가 도메인 규칙을 담고 있으면 companion object 함수로 유지합니다.
- 컬렉션 필드는 `private`인 `MutableSet`/`MutableMap`으로 두고, 외부에는 복사본을 반환하는 함수로 노출합니다. 혼용 기간에는 기존 Java getter 이름을 유지해 호출부 변경을 줄일 수 있습니다.
- **JPQL이 참조하는 필드 이름은 바꾸지 않습니다.** 예를 들어 `ExpenseRepository`의 쿼리가 `e.participantIds`를 쓰므로, `_participantIds` 같은 이름으로 바꾸면 쿼리가 깨집니다.
- 문자열 공백 처리는 기존 기준을 유지합니다. Java `String.strip()`을 Kotlin 기본 `trim()`으로 일괄 대체하면 NBSP(U+00A0) 등의 처리 결과가 달라집니다. 변환 시에는 `trim { Character.isWhitespace(it) }`처럼 기존 판별 기준을 명시하고, 생성·수정 경로 모두 일반 공백·탭·줄바꿈·NBSP 입력 결과를 확인합니다. 공백 제거 범위 변경은 별도 리팩토링 PR로 다룹니다. [Java String.strip() 문서](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/String.html#strip())를 참고합니다.

```kotlin
@Entity
@Table(name = "expenses")
class Expense(
    meetingId: Long,
    payerMemberId: Long,
    title: String,
    amount: Long,
    memo: String?,
    participantIds: Set<Long>,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @Column(nullable = false)
    var meetingId: Long = meetingId
        protected set

    @Column(nullable = false)
    var payerMemberId: Long = payerMemberId
        protected set

    // 기존 String.strip()의 공백 판별 기준을 유지합니다.
    @Column(nullable = false, length = 255)
    var title: String = title.trim { Character.isWhitespace(it) }
        protected set

    @Column(length = 2000)
    var memo: String? = memo
        protected set

    @ElementCollection
    @CollectionTable(name = "expense_participants", joinColumns = [JoinColumn(name = "expense_id")])
    @Column(name = "member_id", nullable = false)
    private var participantIds: MutableSet<Long> = participantIds.toMutableSet()

    fun getParticipantIds(): Set<Long> = participantIds.toSet()

    // ...
}
```

### DTO

- `record`는 `data class`로 바꾸고 프로퍼티는 **`val`만** 씁니다. 중첩 `record`는 중첩 `data class`가 됩니다. 컬렉션도 읽기 전용 타입으로 선언하고, 변경 가능한 입력 컬렉션을 공유하지 않도록 생성 경계에서 복사합니다.
- 요청 DTO의 검증 애노테이션에는 `@field:`를 반드시 붙입니다.
- **오버로드 생성자는 기본 인자로 바꾸되 Java 호출에 필요한 시그니처를 유지합니다.** 아래처럼 마지막 인자만 선택적인 경우 `@JvmOverloads`로 Java용 생성자를 생성할 수 있습니다.

```kotlin
data class ExpenseCreateRequest @JvmOverloads constructor(
    @field:NotBlank
    @field:Size(max = 255)
    val title: String?, // 변환 PR에서는 기존 계약 유지를 위해 nullable
    @field:NotNull
    @field:DecimalMin("1")
    val amount: BigDecimal?,
    // ...
    val randomRemainder: Boolean = false,
)
```

- 정적 팩토리가 있던 응답 DTO는 `companion object`의 `from(...)`으로 옮깁니다. 엔티티는 DTO를 모르고 DTO만 엔티티에 의존합니다. 같은 변환을 하는 `toResponse()` 확장 함수는 중복으로 만들지 않습니다.

```kotlin
data class ExpenseResponse(
    val id: Long,
    val meetingId: Long,
    val title: String,
    // ...
) {
    companion object {
        @JvmStatic // Java의 ExpenseResponse.from(expense) 호출 유지
        fun from(expense: Expense): ExpenseResponse {
            check(expense.id != 0L) { "저장된 지출만 응답으로 변환할 수 있습니다." }
            return ExpenseResponse(
                id = expense.id,
                meetingId = expense.meetingId,
                title = expense.title,
                // ...
            )
        }
    }
}
```

- Kotlin에서 Kotlin 생성자를 호출할 때 인자가 3개를 넘으면 이름 붙은 인자를 씁니다. Java 생성자를 호출할 때는 사용할 수 없습니다.

### 서비스·컴포넌트

- 의존성은 `private val` 주 생성자로 주입하고 `@RequiredArgsConstructor`를 지웁니다.
- `@Transactional`·`@Component` 클래스는 `kotlin-spring` 플러그인이 자동으로 `open` 처리하므로 `open`을 쓰지 않습니다.
- 로거는 `@Slf4j` 대신 companion object에 둡니다. 로그 메시지는 문자열 템플릿 대신 `{}` 자리표시자를 그대로 씁니다.

```kotlin
@Service
class ExpenseService(
    private val expenseRepository: ExpenseRepository,
) {
    companion object {
        private val log = LoggerFactory.getLogger(ExpenseService::class.java)
    }
}
```

### 예외

```kotlin
abstract class BusinessException(
    val code: Int,
    message: String,
) : RuntimeException(message)
```

Java 코드의 `getCode()` 호출은 그대로 동작합니다.

### 설정

- 여러 곳에서 `@Value`로 읽는 값을 `@ConfigurationProperties` 데이터 클래스로 모으는 것은 별도 리팩토링 PR에서 진행합니다. 예: `custom.jwt.*` (`SecurityConfig`, `JwtTokenProvider`에 흩어져 있음). 도입 시 메인 클래스에 `@ConfigurationPropertiesScan`을 추가하고 기존 설정 키·기본값·바인딩 결과를 확인합니다.

```kotlin
@ConfigurationProperties("custom.jwt")
data class JwtProperties(
    val accessSecret: String,
    val refreshSecret: String,
    val accessTokenValiditySeconds: Long,
    val refreshTokenValiditySeconds: Long,
)
```

- `SecurityConfig`는 Spring Security Kotlin DSL(`http { ... }`)로 작성합니다. `import org.springframework.security.config.annotation.web.invoke`가 없으면 DSL이 컴파일되지 않습니다.

## 4. 함수형 문법

### stream → 컬렉션 함수

| Java | Kotlin |
| --- | --- |
| `stream().map(...).toList()` | `map { }` |
| `filter(...).toList()` | `filter { }` |
| `collect(groupingBy(...))` | `groupBy { }` |
| `collect(toMap(k, v))` | `associate { it.k to it.v }` / `associateBy { }` |
| `mapToLong(...).sum()` | `sumOf { }` |
| `sorted(comparing(...))` | `sortedBy { }` |
| `anyMatch(...)` | `any { }` |

```kotlin
// Java: entrySet().stream().sorted(Map.Entry.comparingByKey()).map(...).toList()
participantAmounts.toSortedMap().map { (memberId, amount) -> Share(memberId, amount) }
```

`asSequence()`는 원소가 많고 처리 단계가 여러 개일 때만 씁니다. 작은 컬렉션에서는 오히려 느립니다.

### 식으로 쓰기

- `if`, `when`, `try`는 값을 반환하는 식으로 씁니다.
- enum 분기는 `when`으로 작성하고 `else` 없이 모든 값을 나열합니다. 값이 추가되면 컴파일 에러로 누락을 알 수 있습니다.
- 본문이 식 하나인 함수는 `=`로 씁니다.

### 스코프 함수

| 함수 | 쓰는 곳 |
| --- | --- |
| `let` | null이 아닐 때만 실행, 값 변환 |
| `apply` | 객체 생성 직후 설정 |
| `also` | 로그 같은 부수 효과 |

중첩하지 않습니다. `it`이 무엇인지 불분명하면 이름을 붙입니다.

### `runCatching`

`runCatching`은 `Error`를 포함한 `Throwable` 전체를 잡습니다. 기존 `catch (RuntimeException e)`를 대체하지 않고 `try`/`catch`를 유지합니다.

```kotlin
expiredPollIds.forEach { pollId ->
    try {
        contentPollCloseService.closeOneIfExpired(pollId)
    } catch (e: RuntimeException) {
        log.error("콘텐츠 투표판 마감 실패: pollId={}", pollId, e)
    }
}
```

## 5. 확장 함수

### 쓰는 곳

- 조회 후 없으면 예외를 던지는 반복 코드.

```kotlin
fun ExpenseRepository.getExpense(expenseId: Long): Expense =
    findByIdOrNull(expenseId) ?: throw ExpenseNotFoundException(expenseId)
```

- 상태가 없는 정적 유틸 메서드는 최상위 함수나 확장 함수로 바꿉니다.

### 쓰지 않는 곳

- 응답 DTO의 기존 정적 팩토리 대체. 이 변환은 DTO의 companion object `from(...)`에 둡니다.
- 엔티티의 상태를 바꾸는 비즈니스 규칙은 엔티티의 멤버 함수로 둡니다.
- `private` 상태가 필요하면 멤버 함수로 둡니다.
- `Long`, `String` 같은 표준 타입에 도메인 의미를 붙이지 않습니다(예: `Long.toMeeting()`).

### 위치

- 한 도메인 전용: 해당 도메인 패키지의 `XxxExtensions.kt`
- 여러 도메인 공용: `global/extension`

### Java에서 호출할 때

이행 기간에는 Java 코드가 확장 함수를 호출합니다. 파일에 `@file:JvmName`을 붙여 Java 쪽 이름을 정합니다.

```kotlin
@file:JvmName("ExpenseRepositoryExtensions")
// Java: ExpenseRepositoryExtensions.getExpense(expenseRepository, expenseId)
```

## 6. 테스트

- 테스트 파일 자체의 Kotlin 전환은 대상 운영 코드를 옮긴 PR이 머지된 뒤 별도 PR로 합니다. 운영 코드 전환에 필요한 기존 Java 테스트의 호출 문법 수정은 운영 코드 PR에 포함합니다.
- 테스트 이름은 백틱으로 씁니다: ``fun `지출 등록 시 분담 금액이 저장된다`()``
- 모킹은 Mockito와 `@MockitoBean`을 유지합니다. Kotlin에서 `when`은 키워드이고 Mockito의 `any()`는 null을 반환해 non-null 파라미터에서 실패하므로, mockito-kotlin의 `whenever`, `any()`를 씁니다.
- 주입 필드는 `@Autowired lateinit var`로 씁니다.

## 7. 빌드 설정 선행 작업

| 작업 | 시점 |
| --- | --- |
| `allOpen`에 JPA 어노테이션 추가 (아래) | 첫 엔티티 전환 전 |
| `-Xemit-jvm-type-annotations` 컴파일러 옵션 유지 | 첫 요청 DTO 전환 전 (현재 빌드에 반영) |
| mockito-kotlin 테스트 의존성 추가 | 첫 테스트 전환 전 |
| (필요할 때만) `org.jetbrains.kotlin.plugin.lombok` | Kotlin이 Java Lombok 클래스를 참조해야 할 때 |
| Lombok 의존성·플러그인 제거 | 마지막 Java 파일 전환 후 |

현재 프로젝트의 Kotlin **2.3.21**에서는 `kotlin-jpa`가 기본 생성자를 만들고, JPA 엔티티의 `open` 처리는 아래 `allOpen` 설정으로 지정합니다. `kotlin-spring`은 Spring 컴포넌트에 적용됩니다. 엔티티가 `final`이면 Hibernate가 상속 프록시를 만들 수 없으므로 첫 엔티티 전환 전에 설정을 반영합니다. 컴파일러 버전을 올릴 때는 플러그인의 JPA 지원 범위를 다시 확인합니다.

```groovy
allOpen {
    annotation 'jakarta.persistence.Entity'
    annotation 'jakarta.persistence.MappedSuperclass'
    annotation 'jakarta.persistence.Embeddable'
}
```

`kotlin-jpa`의 no-arg 생성자는 기본적으로 필드 초기화 식과 초기화 블록을 실행하지 않습니다(`invokeInitializers` 기본값은 `false`). 일반 생성자로 생성한 객체와 Hibernate가 no-arg 생성자로 재구성한 객체를 구분해 검증합니다. DB 매핑 필드는 로딩 시 채워지지만, 비영속 필드나 초기값에 의존하는 로직은 별도로 확인합니다. 초기화 실행이 필요하면 `invokeInitializers` 설정과 기존 초기화 코드의 안전성을 함께 검토합니다. [Kotlin no-arg 플러그인 문서](https://kotlinlang.org/docs/no-arg-plugin.html)를 참고합니다.

## 8. PR 체크리스트

```markdown
- [ ] `./gradlew ktlintFormat`을 먼저 실행해 변경 범위를 확인한 뒤 `./gradlew ktlintCheck test` 통과
- [ ] 변환과 동작 변경을 분리했다
- [ ] 남은 Java 호출부의 접근자·생성자·정적 메서드·반환 타입 호환성을 확인했다
- [ ] Java 호출자가 사라진 호환 애노테이션(`@JvmStatic`, `@JvmOverloads`)과 임시 호환 메서드를 제거했다 (유지 시 사유 기재)
- [ ] 기존 테스트의 검증 의도와 기대 결과를 유지했다
- [ ] (엔티티 전환) 저장 후 영속성 컨텍스트를 비우고 재조회해 매핑과 no-arg 초기화에 의존하는 컬렉션·비영속 상태를 확인했다
- [ ] (첫 도메인 검증) `Expense`의 ID 기반 신규 판별과 `Settlement`의 nullable `@Version` 기반 신규 판별·버전 증가를 확인했다
- [ ] (DTO 전환) 정상 요청과 잘못된 입력의 API 계약을 확인했다
- [ ] (컬렉션 요청 전환) `-Xemit-jvm-type-annotations` 설정을 확인하고, 목록 누락·null·빈 목록·null 원소·잘못된 원소 검증을 API 테스트로 확인했다
- [ ] (문자열 처리 전환) 생성·수정 경로의 기존 공백 판별 기준을 유지하고 NBSP 등 경계 입력을 확인했다
- [ ] `!!` 없음 (있다면 사유 주석)
- [ ] Java 반환값을 받을 때 타입을 명시했다
- [ ] `Optional` 대신 `T?` / `findByIdOrNull`
- [ ] 기본은 `val`, 의존성은 `private val` 생성자 주입으로 작성했다
- [ ] 엔티티는 일반 `class`, 자동 생성 ID는 `var id: Long = 0`, 매핑 필드는 `val` 없이 `var` + `protected set`으로 선언했다
- [ ] 엔티티의 동일성·해시값 처리를 확인하고 `toString`에서 연관관계를 제외했다
- [ ] 목록은 내부 `MutableList` / 외부 `List`로 분리하고, 기존 `Set`·`Map`의 의미를 유지했다
- [ ] DTO는 `data class`와 `val`만 사용하고, 요청 검증 애노테이션에는 `@field:`를 붙였다
- [ ] (변환 PR) 요청 DTO의 기존 Java 참조 타입은 nullable과 기존 검증 제약을 유지하고, 원시 타입은 기존 기본값·누락·null 처리 및 Java 호출 시그니처를 확인했다
- [ ] 응답 DTO의 기존 정적 팩토리는 companion object `from(...)`으로 옮겼다
- [ ] 기본 인자 적용 시 필요한 Java용 생성자 시그니처를 유지했다
- [ ] `lateinit`은 JPA·테스트 설정에 제한하고, 연속된 `?.`는 한 줄에 하나씩 나눴다
- [ ] stream을 Kotlin 컬렉션 함수로 바꿨다
```

## 참고 문서

- [Spring Framework: Kotlin](https://docs.spring.io/spring-framework/reference/languages/kotlin.html)
- [Spring Boot: Kotlin Support](https://docs.spring.io/spring-boot/reference/features/kotlin.html)
- [Spring Guide: Building web applications with Spring Boot and Kotlin](https://spring.io/guides/tutorials/spring-boot-kotlin)
- [Spring Security: Kotlin Configuration](https://docs.spring.io/spring-security/reference/servlet/configuration/kotlin.html)
- [Kotlin: Calling Java from Kotlin](https://kotlinlang.org/docs/java-interop.html) / [Calling Kotlin from Java](https://kotlinlang.org/docs/java-to-kotlin-interop.html)
- [Kotlin: All-open](https://kotlinlang.org/docs/all-open-plugin.html) / [No-arg](https://kotlinlang.org/docs/no-arg-plugin.html) / [Lombok](https://kotlinlang.org/docs/lombok.html) 컴파일러 플러그인
- [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- [mockito-kotlin](https://github.com/mockito/mockito-kotlin)
