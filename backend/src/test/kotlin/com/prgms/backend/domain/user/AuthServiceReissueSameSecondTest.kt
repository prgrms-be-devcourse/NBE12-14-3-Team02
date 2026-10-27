package com.prgms.backend.domain.user

import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.service.AuthService
import com.prgms.backend.global.exception.custom.user.ReissueFailException
import com.prgms.backend.security.JwtTokenProvider
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.Optional
import javax.crypto.SecretKey

internal class AuthServiceReissueSameSecondTest {
    private lateinit var refreshKey: SecretKey

    private lateinit var userRepository: UserRepository
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var authService: AuthService

    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        userRepository = mock()
        // 토큰 문자열 자체를 비교해야 하므로 JwtTokenProvider는 실제 객체를 사용
        refreshKey =
            Jwts.SIG.HS256
                .key()
                .build()
        jwtTokenProvider =
            JwtTokenProvider(
                Jwts.SIG.HS256
                    .key()
                    .build(),
                refreshKey,
                CLOCK,
            )
        ReflectionTestUtils.setField(jwtTokenProvider, "accessExpiration", 1800L)
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshExpiration", 1209600L)

        authService =
            AuthService(
                userRepository = userRepository,
                passwordEncoder = mock(),
                jwtTokenProvider = jwtTokenProvider,
            )

        user = User("user@test.com", "회원", "encoded-password")
        ReflectionTestUtils.setField(user, "id", 1L)

        whenever(userRepository.findById(1L))
            .thenReturn(Optional.of(user))
    }

    @Test
    fun `같은 초 안에 같은 회원의 refresh token을 두 번 만들어도 서로 다른 토큰이 생성된다`() {
        // when
        val first = jwtTokenProvider.createRefreshToken(1L)
        val second = jwtTokenProvider.createRefreshToken(1L)

        // then
        assertNotEquals(first, second)
        assertSameIssueTime(first, second)
        assertNotNull(claims(first).id)
        assertNotEquals(claims(first).id, claims(second).id)
    }

    @Test
    fun `발급과 같은 초 안에 재발급해도 기존과 다른 refresh token이 재발급된다`() {
        // given
        val oldRefreshToken = jwtTokenProvider.createRefreshToken(1L)
        user.updateRefreshToken(oldRefreshToken)

        // when
        val reissued = authService.reissue(oldRefreshToken)

        // then
        assertNotEquals(oldRefreshToken, reissued.refreshToken)
        assertEquals(reissued.refreshToken, user.refreshToken)
        assertSameIssueTime(oldRefreshToken, reissued.refreshToken)
        assertTrue(jwtTokenProvider.validateAccessToken(reissued.accessToken))
        assertEquals(1L, jwtTokenProvider.getAccessUserId(reissued.accessToken))
    }

    @Test
    fun `같은 초 안에 재발급해도 이미 사용한 refresh token으로는 다시 재발급받을 수 없다`() {
        // given
        val oldRefreshToken = jwtTokenProvider.createRefreshToken(1L)
        user.updateRefreshToken(oldRefreshToken)
        val reissued = authService.reissue(oldRefreshToken)

        // when & then
        assertThrows<ReissueFailException> { authService.reissue(oldRefreshToken) }
        assertEquals(reissued.refreshToken, user.refreshToken)
        assertDoesNotThrow { authService.reissue(reissued.refreshToken) }
    }

    private fun assertSameIssueTime(
        first: String,
        second: String,
    ) {
        assertEquals(CLOCK.instant(), claims(first).issuedAt.toInstant())
        assertEquals(claims(first).issuedAt, claims(second).issuedAt)
        assertEquals(claims(first).expiration, claims(second).expiration)
    }

    private fun claims(token: String): Claims =
        Jwts
            .parser()
            .verifyWith(refreshKey)
            .clock { Date.from(CLOCK.instant()) }
            .build()
            .parseSignedClaims(token)
            .payload

    companion object {
        private val CLOCK: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    }
}
