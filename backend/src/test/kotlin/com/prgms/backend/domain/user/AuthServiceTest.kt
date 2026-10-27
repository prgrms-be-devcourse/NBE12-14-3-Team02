package com.prgms.backend.domain.user

import com.prgms.backend.domain.user.dto.request.LogInRequest
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.service.AuthService
import com.prgms.backend.global.exception.custom.user.DuplicateEmailNickname
import com.prgms.backend.global.exception.custom.user.LoginFailException
import com.prgms.backend.global.exception.custom.user.PasswordMismatchException
import com.prgms.backend.global.exception.custom.user.ReissueFailException
import com.prgms.backend.global.exception.custom.user.UserNotFoundException
import com.prgms.backend.security.JwtTokenProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import java.util.Optional

internal class AuthServiceTest {
    private lateinit var userRepository: UserRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var authService: AuthService

    private lateinit var user: User

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

        user = User("user@test.com", "회원", "encoded-password")
        ReflectionTestUtils.setField(user, "id", 1L)
        user.updateRefreshToken("refresh-token")
    }

    @Test
    fun `비밀번호와 비밀번호 확인이 일치하면 예외가 발생하지 않는다`() {
        // when & then
        assertDoesNotThrow {
            authService.validateConfirmPassword("Passw0rd!", "Passw0rd!")
        }
    }

    @Test
    fun `비밀번호와 비밀번호 확인이 다르면 예외가 발생한다`() {
        // when & then
        assertThrows<PasswordMismatchException> {
            authService.validateConfirmPassword("Passw0rd!", "Different0!")
        }
    }

    @Test
    fun `회원가입 시 비밀번호를 암호화해 저장하고 회원 id를 반환한다`() {
        // given
        whenever(userRepository.existsByEmail("new@test.com")).thenReturn(false)
        whenever(userRepository.existsByNickname("새회원")).thenReturn(false)
        whenever(passwordEncoder.encode("Passw0rd!")).thenReturn("encoded-Passw0rd!")
        whenever(userRepository.saveAndFlush(any<User>())).thenAnswer { invocation ->
            val saved: User = invocation.getArgument(0)
            ReflectionTestUtils.setField(saved, "id", 2L)
            saved
        }

        // when
        val userId = authService.signup("new@test.com", "새회원", "Passw0rd!", "Passw0rd!")

        // then
        val captor = argumentCaptor<User>()
        verify(userRepository).saveAndFlush(captor.capture())

        val saved = captor.firstValue
        assertEquals(2L, userId)
        assertEquals("new@test.com", saved.email)
        assertEquals("새회원", saved.nickname)
        assertEquals("encoded-Passw0rd!", saved.password)
    }

    @Test
    fun `비밀번호 확인이 일치하지 않으면 회원가입할 수 없다`() {
        // when & then
        assertThrows<PasswordMismatchException> {
            authService.signup("new@test.com", "새회원", "Passw0rd!", "Different0!")
        }

        verify(userRepository, never()).existsByEmail(any())
        verify(userRepository, never()).saveAndFlush(any<User>())
    }

    @Test
    fun `이미 사용 중인 이메일로는 회원가입할 수 없다`() {
        // given
        whenever(userRepository.existsByEmail("user@test.com")).thenReturn(true)

        // when & then
        assertThrows<DuplicateEmailNickname> {
            authService.signup("user@test.com", "새회원", "Passw0rd!", "Passw0rd!")
        }

        verify(userRepository, never()).saveAndFlush(any<User>())
    }

    @Test
    fun `이미 사용 중인 닉네임으로는 회원가입할 수 없다`() {
        // given
        whenever(userRepository.existsByEmail("new@test.com")).thenReturn(false)
        whenever(userRepository.existsByNickname("회원")).thenReturn(true)

        // when & then
        assertThrows<DuplicateEmailNickname> {
            authService.signup("new@test.com", "회원", "Passw0rd!", "Passw0rd!")
        }

        verify(userRepository, never()).saveAndFlush(any<User>())
    }

    @Test
    fun `로그인에 성공하면 토큰을 발급하고 refresh token을 저장한다`() {
        // given
        whenever(userRepository.findByEmail("user@test.com")).thenReturn(user)
        whenever(passwordEncoder.matches("password", "encoded-password")).thenReturn(true)
        whenever(jwtTokenProvider.createAccessToken(1L)).thenReturn("new-access-token")
        whenever(jwtTokenProvider.createRefreshToken(1L)).thenReturn("new-refresh-token")

        // when
        val tokenPair = authService.login(LogInRequest("user@test.com", "password"))

        // then
        assertEquals("new-access-token", tokenPair.accessToken)
        assertEquals("new-refresh-token", tokenPair.refreshToken)
        assertEquals("new-refresh-token", user.refreshToken)
    }

    @Test
    fun `가입되지 않은 이메일로는 로그인할 수 없다`() {
        // given
        whenever(userRepository.findByEmail("none@test.com")).thenReturn(null)

        // when & then
        assertThrows<UserNotFoundException> {
            authService.login(LogInRequest("none@test.com", "password"))
        }
    }

    @Test
    fun `비밀번호가 틀리면 로그인할 수 없다`() {
        // given
        whenever(userRepository.findByEmail("user@test.com")).thenReturn(user)
        whenever(passwordEncoder.matches("wrong", "encoded-password")).thenReturn(false)

        // when & then
        assertThrows<LoginFailException> {
            authService.login(LogInRequest("user@test.com", "wrong"))
        }

        verify(jwtTokenProvider, never()).createAccessToken(any())
        assertEquals("refresh-token", user.refreshToken)
    }

    @Test
    fun `저장된 refresh token과 일치하면 토큰을 재발급하고 refresh token을 교체한다`() {
        // given
        givenValidRefreshToken("refresh-token")
        whenever(userRepository.findById(1L)).thenReturn(Optional.of(user))
        whenever(jwtTokenProvider.createAccessToken(1L)).thenReturn("new-access-token")
        whenever(jwtTokenProvider.createRefreshToken(1L)).thenReturn("new-refresh-token")

        // when
        val tokenPair = authService.reissue("refresh-token")

        // then
        assertEquals("new-access-token", tokenPair.accessToken)
        assertEquals("new-refresh-token", tokenPair.refreshToken)
        assertEquals("new-refresh-token", user.refreshToken)
    }

    @Test
    fun `유효하지 않은 refresh token으로는 재발급받을 수 없다`() {
        // given
        whenever(jwtTokenProvider.validateRefreshToken("invalid-token")).thenReturn(false)

        // when & then
        assertThrows<ReissueFailException> {
            authService.reissue("invalid-token")
        }

        verify(userRepository, never()).findById(any())
        verify(jwtTokenProvider, never()).createAccessToken(any())
    }

    @Test
    fun `저장된 refresh token과 다르면 재발급받을 수 없다`() {
        // given
        givenValidRefreshToken("old-refresh-token")
        whenever(userRepository.findById(1L)).thenReturn(Optional.of(user))

        // when & then
        assertThrows<ReissueFailException> {
            authService.reissue("old-refresh-token")
        }

        verify(jwtTokenProvider, never()).createAccessToken(any())
        assertEquals("refresh-token", user.refreshToken)
    }

    @Test
    fun `로그아웃한 회원은 refresh token이 없어 재발급받을 수 없다`() {
        // given
        user.updateRefreshToken(null)
        givenValidRefreshToken("refresh-token")
        whenever(userRepository.findById(1L)).thenReturn(Optional.of(user))

        // when & then
        assertThrows<ReissueFailException> {
            authService.reissue("refresh-token")
        }
    }

    @Test
    fun `존재하지 않는 회원의 refresh token으로는 재발급받을 수 없다`() {
        // given
        givenValidRefreshToken("refresh-token")
        whenever(userRepository.findById(1L)).thenReturn(Optional.empty())

        // when & then
        assertThrows<UserNotFoundException> {
            authService.reissue("refresh-token")
        }
    }

    @Test
    fun `로그아웃하면 저장된 refresh token을 삭제한다`() {
        // given
        whenever(userRepository.findById(1L)).thenReturn(Optional.of(user))

        // when
        authService.logout(1L, "refresh-token")

        // then
        assertNull(user.refreshToken)
    }

    @Test
    fun `저장된 refresh token과 다른 토큰으로 로그아웃하면 refresh token을 유지한다`() {
        // given
        whenever(userRepository.findById(1L)).thenReturn(Optional.of(user))

        // when
        authService.logout(1L, "other-device-token")

        // then
        assertEquals("refresh-token", user.refreshToken)
    }

    @Test
    fun `존재하지 않는 회원은 로그아웃할 수 없다`() {
        // given
        whenever(userRepository.findById(99L)).thenReturn(Optional.empty())

        // when & then
        assertThrows<UserNotFoundException> {
            authService.logout(99L, "refresh-token")
        }
    }

    private fun givenValidRefreshToken(refreshToken: String) {
        whenever(jwtTokenProvider.validateRefreshToken(refreshToken)).thenReturn(true)
        whenever(jwtTokenProvider.getRefreshUserId(refreshToken)).thenReturn(1L)
    }
}
