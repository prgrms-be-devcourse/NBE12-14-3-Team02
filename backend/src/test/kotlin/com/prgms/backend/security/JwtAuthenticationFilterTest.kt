package com.prgms.backend.security

import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.domain.user.repository.UserRepository
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.assertIs
import kotlin.test.assertNotNull

internal class JwtAuthenticationFilterTest {
    private lateinit var tokenProvider: JwtTokenProvider
    private lateinit var userRepository: UserRepository
    private lateinit var filter: JwtAuthenticationFilter

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.clearContext()
        tokenProvider = mock()
        userRepository = mock()
        filter = JwtAuthenticationFilter(tokenProvider, userRepository)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `유효한 토큰과 활성 회원이면 ID만 가진 Principal로 인증한다`() {
        givenValidToken()
        whenever(userRepository.existsByIdAndStatusAndDeletedAtIsNull(1L, UserStatus.ACTIVE))
            .thenReturn(true)

        filter.doFilter(
            requestWithToken(),
            MockHttpServletResponse(),
            FilterChain { _, _ ->
                val authentication = SecurityContextHolder.getContext().authentication
                assertNotNull(authentication)
                assertTrue(authentication.isAuthenticated)
                val principal = assertIs<SecurityUser>(authentication.principal)
                assertEquals(1L, principal.id)
            },
        )

        verify(userRepository).existsByIdAndStatusAndDeletedAtIsNull(1L, UserStatus.ACTIVE)
        verifyNoMoreInteractions(userRepository)
    }

    @Test
    fun `탈퇴 또는 삭제되었거나 존재하지 않는 회원은 유효한 토큰이 있어도 인증하지 않는다`() {
        givenValidToken()
        whenever(userRepository.existsByIdAndStatusAndDeletedAtIsNull(1L, UserStatus.ACTIVE))
            .thenReturn(false)
        val principal = SecurityUser(1L)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)

        filter.doFilter(
            requestWithToken(),
            MockHttpServletResponse(),
            FilterChain { _, _ ->
                assertNull(SecurityContextHolder.getContext().authentication)
            },
        )

        verify(userRepository).existsByIdAndStatusAndDeletedAtIsNull(1L, UserStatus.ACTIVE)
    }

    @Test
    fun `유효하지 않은 토큰은 회원 DB를 조회하지 않는다`() {
        whenever(tokenProvider.validateAccessToken("token")).thenReturn(false)

        filter.doFilter(
            requestWithToken(),
            MockHttpServletResponse(),
            FilterChain { _, _ ->
                assertNull(SecurityContextHolder.getContext().authentication)
            },
        )

        verifyNoInteractions(userRepository)
        verify(tokenProvider, never()).getAccessUserId(any())
    }

    @Test
    fun `토큰이 없는 공개 요청은 DB 조회 없이 다음 필터로 진행한다`() {
        val request = MockHttpServletRequest("POST", "/api/auth/log-in")

        filter.doFilter(
            request,
            MockHttpServletResponse(),
            FilterChain { _, _ ->
                assertNull(SecurityContextHolder.getContext().authentication)
            },
        )

        verifyNoInteractions(tokenProvider, userRepository)
    }

    private fun givenValidToken() {
        whenever(tokenProvider.validateAccessToken("token")).thenReturn(true)
        whenever(tokenProvider.getAccessUserId("token")).thenReturn(1L)
    }

    private fun requestWithToken(): MockHttpServletRequest =
        MockHttpServletRequest("GET", "/api/user/me").apply {
            addHeader("Authorization", "Bearer token")
        }
}
