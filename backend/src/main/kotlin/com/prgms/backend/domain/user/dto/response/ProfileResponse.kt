package com.prgms.backend.domain.user.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class ProfileResponse(
    @field:Schema(description = "이메일", example = "test@example.com")
    val email: String,
    @field:Schema(description = "닉네임", example = "홍길동")
    val nickname: String,
)
