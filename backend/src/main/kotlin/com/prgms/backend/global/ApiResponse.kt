package com.prgms.backend.global

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder

/**
 * API 응답을 감싸는 공통 포맷.
 * 성공/실패 응답을 {success, code, message, data} 구조로 표현한다.
 * 실제 HTTP 204 응답은 본문 없이 반환한다.
 */
@JsonPropertyOrder("success", "code", "message", "data")
@ConsistentCopyVisibility
data class ApiResponse<T> private constructor(
    @get:JvmName("isSuccess")
    @get:JsonProperty("success")
    val success: Boolean,
    val code: Int,
    val message: String?,
    val data: T?,
) {
    private constructor() : this(false, 0, null, null)

    // 남아 있는 Java 호출자의 정적 팩토리 호출을 유지한다.
    companion object {
        @JvmStatic
        fun <T> success(
            code: Int,
            data: T?,
        ): ApiResponse<T> = ApiResponse(true, code, "", data)

        @JvmStatic
        fun <T> success(
            code: Int,
            message: String?,
            data: T?,
        ): ApiResponse<T> = ApiResponse(true, code, message, data)

        @JvmStatic
        fun noContentSuccess(): ApiResponse<Void> = ApiResponse(true, 204, "", null)

        @JvmStatic
        fun noContentSuccess(message: String?): ApiResponse<Void> = ApiResponse(true, 204, message, null)

        @JvmStatic
        fun <T> error(
            code: Int,
            message: String?,
            data: T?,
        ): ApiResponse<T> = ApiResponse(false, code, message, data)

        @JvmStatic
        fun error(
            code: Int,
            message: String?,
        ): ApiResponse<Void> = ApiResponse(false, code, message, null)
    }
}
