package com.prgms.backend.security

import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Service
class JwtTokenProvider(
    private val accessSecretKey: SecretKey,
    private val refreshSecretKey: SecretKey,
    private val clock: Clock,
) {
    @Autowired
    constructor(
        @Qualifier("accessSecretKey") accessSecretKey: SecretKey,
        @Qualifier("refreshSecretKey") refreshSecretKey: SecretKey,
    ) : this(accessSecretKey, refreshSecretKey, Clock.systemUTC())

    @Value("\${custom.jwt.access-token-validity-seconds}")
    private var accessExpiration: Long = 0

    @Value("\${custom.jwt.refresh-token-validity-seconds}")
    private var refreshExpiration: Long = 0

    init {
        require(!accessSecretKey.encoded.contentEquals(refreshSecretKey.encoded)) {
            "Access와 refresh JWT key는 달라야 합니다."
        }
    }

    fun createAccessToken(userId: Long): String {
        val now: Date = Date.from(clock.instant())
        val expiryDate = Date(now.time + accessExpiration * 1000)

        return Jwts
            .builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(accessSecretKey)
            .compact()
    }

    fun createRefreshToken(userId: Long): String {
        val now: Date = Date.from(clock.instant())
        val expiryDate = Date(now.time + refreshExpiration * 1000)

        return Jwts
            .builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiration(expiryDate)
            .id(UUID.randomUUID().toString()) // jti 추가
            .signWith(refreshSecretKey)
            .compact()
    }

    fun validateAccessToken(token: String?): Boolean = validateToken(token, accessSecretKey)

    fun validateRefreshToken(token: String?): Boolean = validateToken(token, refreshSecretKey)

    private fun validateToken(
        token: String?,
        secretKey: SecretKey,
    ): Boolean =
        try {
            Jwts
                .parser()
                .verifyWith(secretKey)
                .clock { Date.from(clock.instant()) }
                .build()
                .parseSignedClaims(token)
            true
        } catch (e: Exception) {
            false
        }

    fun getAccessUserId(token: String?): Long = getUserId(token, accessSecretKey)

    fun getRefreshUserId(token: String?): Long = getUserId(token, refreshSecretKey)

    private fun getUserId(
        token: String?,
        secretKey: SecretKey,
    ): Long {
        val subject: String =
            Jwts
                .parser()
                .verifyWith(secretKey)
                .clock { Date.from(clock.instant()) }
                .build()
                .parseSignedClaims(token)
                .payload
                .subject

        return subject.toLong()
    }
}
