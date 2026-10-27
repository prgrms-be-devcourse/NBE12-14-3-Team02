package com.prgms.backend.domain.user

import com.prgms.backend.domain.user.dto.request.LogInRequest
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.service.AuthService
import com.prgms.backend.global.exception.custom.user.LoginFailException
import com.prgms.backend.global.exception.custom.user.WithdrawUserException
import com.prgms.backend.security.JwtTokenProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

internal class AuthServiceWithdrawnUserTest {
    private lateinit var userRepository: UserRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var authService: AuthService

    private lateinit var withdrawnUser: User

    @BeforeEach
    fun setUp() {
        userRepository = mock()
        passwordEncoder = mock()
        jwtTokenProvider = mock()

        authService =
            AuthService(
                userRepository = userRepository,
                passwordEncoder = passwordEncoder,
                jwtTokenProvider = jwtTokenProvider,
            )

        withdrawnUser = User("user@test.com", "탈퇴회원", "encoded-password")
        ReflectionTestUtils.setField(withdrawnUser, "id", 1L)
        withdrawnUser.updateStatus(UserStatus.WITHDRAWN)
        withdrawnUser.updateDeletedAt(LocalDateTime.now())

        whenever(userRepository.findByEmail("user@test.com"))
            .thenReturn(withdrawnUser)
    }

    @Test
    fun `탈퇴한 회원은 비밀번호가 맞아도 로그인할 수 없다`() {
        // given
        whenever(passwordEncoder.matches("password", "encoded-password"))
            .thenReturn(true)

        // when & then
        assertThrows<WithdrawUserException> {
            authService.login(LogInRequest("user@test.com", "password"))
        }

        verify(jwtTokenProvider, never()).createAccessToken(any())
        verify(jwtTokenProvider, never()).createRefreshToken(any())
    }

    @Test
    fun `탈퇴한 회원이라도 비밀번호가 틀리면 탈퇴 여부를 알리지 않고 로그인 실패 처리한다`() {
        // given
        whenever(passwordEncoder.matches("wrong", "encoded-password"))
            .thenReturn(false)

        // when & then
        assertThrows<LoginFailException> {
            authService.login(LogInRequest("user@test.com", "wrong"))
        }
    }

    @Test
    fun `탈퇴한 회원은 토큰을 재발급받을 수 없다`() {
        // given
        whenever(jwtTokenProvider.validateRefreshToken("old-refresh-token"))
            .thenReturn(true)
        whenever(jwtTokenProvider.getRefreshUserId("old-refresh-token"))
            .thenReturn(1L)
        whenever(userRepository.findById(1L))
            .thenReturn(Optional.of(withdrawnUser))

        // when & then
        assertThrows<WithdrawUserException> {
            authService.reissue("old-refresh-token")
        }

        verify(jwtTokenProvider, never()).createAccessToken(any())
    }
}
