package com.prgms.backend.global.exception

/**
 * 의도적으로 던지는 모든 비즈니스 예외의 부모.
 * 구체적인 상황을 나타내는 자식 클래스를 통해 사용하고 GlobalExceptionHandler에서 함께 처리한다.
 */
abstract class BusinessException protected constructor(
    val code: Int,
    message: String?,
) : RuntimeException(message)
