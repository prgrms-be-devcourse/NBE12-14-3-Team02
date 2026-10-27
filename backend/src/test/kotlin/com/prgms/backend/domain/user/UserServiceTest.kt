package com.prgms.backend.domain.user

import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.service.AuthService
import com.prgms.backend.domain.user.service.UserService
import com.prgms.backend.global.exception.custom.user.DuplicateEmailNickname
import com.prgms.backend.global.exception.custom.user.PasswordMismatchException
import com.prgms.backend.global.exception.custom.user.UserNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.Optional

internal class UserServiceTest {
    private lateinit var userRepository: UserRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var authService: AuthService

    private lateinit var userService: UserService

    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        userRepository = mock()
        passwordEncoder = mock()
        authService = mock()

        userService =
            UserService(
                userRepository = userRepository,
                passwordEncoder = passwordEncoder,
                authService = authService,
            )

        user = User("user@test.com", "회원", "encoded-password")
        ReflectionTestUtils.setField(user, "id", 1L)
    }

    @Test
    fun `프로필을 조회하면 이메일과 닉네임을 반환한다`() {
        // given
        whenever(userRepository.findById(1L)).thenReturn(Optional.of(user))

        // when
        val response = userService.getProfile(1L)

        // then
        assertEquals("user@test.com", response.email)
        assertEquals("회원", response.nickname)
    }

    @Test
    fun `존재하지 않는 회원의 프로필은 조회할 수 없다`() {
        // given
        whenever(userRepository.findById(99L)).thenReturn(Optional.empty())

        // when & then
        assertThrows<UserNotFoundException> {
            userService.getProfile(99L)
        }
    }

    @Test
    fun `사용 중이지 않은 닉네임으로 변경하면 변경된 프로필을 반환한다`() {
        // given
        whenever(authService.checkNickname("새닉네임")).thenReturn(false)
        whenever(userRepository.findById(1L)).thenReturn(Optional.of(user))

        // when
        val response = userService.updateNickname(1L, "새닉네임")

        // then
        assertEquals("새닉네임", user.nickname)
        assertEquals("user@test.com", response.email)
        assertEquals("새닉네임", response.nickname)
    }

    @Test
    fun `이미 사용 중인 닉네임으로는 변경할 수 없다`() {
        // given
        whenever(authService.checkNickname("중복닉네임")).thenReturn(true)

        // when & then
        assertThrows<DuplicateEmailNickname> {
            userService.updateNickname(1L, "중복닉네임")
        }

        verify(userRepository, never()).findById(any())
        assertEquals("회원", user.nickname)
    }

    @Test
    fun `존재하지 않는 회원은 닉네임을 변경할 수 없다`() {
        // given
        whenever(authService.checkNickname("새닉네임")).thenReturn(false)
        whenever(userRepository.findById(99L)).thenReturn(Optional.empty())

        // when & then
        assertThrows<UserNotFoundException> {
            userService.updateNickname(99L, "새닉네임")
        }
    }

    @Test
    fun `현재 비밀번호가 일치하면 새 비밀번호를 암호화해 변경한다`() {
        // given
        whenever(userRepository.findById(1L)).thenReturn(Optional.of(user))
        whenever(passwordEncoder.matches("password", "encoded-password")).thenReturn(true)
        whenever(passwordEncoder.encode("NewPassw0rd!")).thenReturn("encoded-NewPassw0rd!")

        // when
        userService.updatePassword(1L, "password", "NewPassw0rd!")

        // then
        assertEquals("encoded-NewPassw0rd!", user.password)
    }

    @Test
    fun `현재 비밀번호가 틀리면 비밀번호를 변경할 수 없다`() {
        // given
        whenever(userRepository.findById(1L)).thenReturn(Optional.of(user))
        whenever(passwordEncoder.matches("wrong", "encoded-password")).thenReturn(false)

        // when & then
        assertThrows<PasswordMismatchException> {
            userService.updatePassword(1L, "wrong", "NewPassw0rd!")
        }

        verify(passwordEncoder, never()).encode(any())
        assertEquals("encoded-password", user.password)
    }

    @Test
    fun `존재하지 않는 회원은 비밀번호를 변경할 수 없다`() {
        // given
        whenever(userRepository.findById(99L)).thenReturn(Optional.empty())

        // when & then
        assertThrows<UserNotFoundException> {
            userService.updatePassword(99L, "password", "NewPassw0rd!")
        }
    }
}
