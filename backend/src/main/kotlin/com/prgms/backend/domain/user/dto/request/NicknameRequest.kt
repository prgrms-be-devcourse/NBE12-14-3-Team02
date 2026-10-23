package com.prgms.backend.domain.user.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

data class NicknameRequest(
    @field:Schema(description = "닉네임", example = "홍길동")
    @field:NotBlank
    val nickname: String?,
)
