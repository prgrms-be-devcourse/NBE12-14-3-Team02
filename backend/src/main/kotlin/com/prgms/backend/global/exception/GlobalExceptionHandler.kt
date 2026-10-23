package com.prgms.backend.global.exception

import com.prgms.backend.global.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {
    // 비즈니스 예외 안에 담긴 code/message를 그대로 응답한다.
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity
            .status(e.code)
            .body(ApiResponse.error(e.code, e.message))

    // 여러 필드가 동시에 실패하면 첫 번째 에러 메시지만 응답한다.
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(e: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Void>> {
        val fieldError = e.bindingResult.fieldError
        val message: String? = if (fieldError != null) fieldError.defaultMessage else "잘못된 요청입니다."
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(400, message))
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleMethodValidationException(e: HandlerMethodValidationException): ResponseEntity<ApiResponse<Void>> {
        val message: String? = e.allErrors[0].defaultMessage
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(400, message))
    }

    // 예상하지 못한 예외는 서버에만 기록하고 고정 메시지로 응답한다.
    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ApiResponse<Void>> {
        log.error("예상하지 못한 예외 발생", e)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(500, "서버 오류가 발생했습니다."))
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFoundException(e: NoResourceFoundException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error(404, e.message))

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameterException(
        e: MissingServletRequestParameterException,
    ): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(400, e.message))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(e: HttpMessageNotReadableException): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(400, e.message))

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleHttpMediaTypeNotSupportedException(
        e: HttpMediaTypeNotSupportedException,
    ): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity
            .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .headers(e.headers)
            .body(ApiResponse.error(415, "지원하지 않는 Content-Type입니다."))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleHttpRequestMethodNotSupportedException(
        e: HttpRequestMethodNotSupportedException,
    ): ResponseEntity<ApiResponse<Void>> =
        ResponseEntity
            .status(HttpStatus.METHOD_NOT_ALLOWED)
            .headers(e.headers)
            .body(ApiResponse.error(405, "지원하지 않는 HTTP 메서드입니다."))

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
