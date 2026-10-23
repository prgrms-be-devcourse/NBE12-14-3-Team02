package com.prgms.backend.domain.user.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class ReissueResponse(
    @field:Schema(description = "재발급된 액세스 토큰", example = "eyJhbGciOiJIUzI1NiJ9...")
    val accessToken: String,
)
