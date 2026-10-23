package com.prgms.backend.domain.user.dto

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
)
