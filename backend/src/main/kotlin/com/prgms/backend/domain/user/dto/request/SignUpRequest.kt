package com.prgms.backend.domain.user.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class SignUpRequest(
    @field:Schema(description = "이메일", example = "test@example.com")
    @field:Email
    @field:NotBlank
    val email: String?,
    @field:Schema(description = "닉네임", example = "홍길동")
    @field:NotBlank
    val nickname: String?,
    @field:Schema(
        description = "비밀번호 (영문, 숫자, 특수문자 포함 8자 이상)",
        example = "Passw0rd!",
        pattern = PASSWORD_PATTERN,
    )
    @field:Pattern(
        regexp = PASSWORD_PATTERN,
        message = "비밀번호는 영문, 숫자, 특수문자를 포함해 8자 이상이어야 합니다.",
    )
    @field:NotBlank
    val password: String?,
    @field:Schema(description = "비밀번호 확인", example = "Passw0rd!")
    @field:NotBlank
    val confirmPassword: String?,
) {
    companion object {
        // 영문 1자 이상 + 숫자 1자 이상 + 특수문자 1자 이상 + 총 8자 이상
        const val PASSWORD_PATTERN = """^(?=.*[A-Za-z])(?=.*\d)(?=.*[!@#$%^&*()_+\-=]).{8,}$"""
    }
}
