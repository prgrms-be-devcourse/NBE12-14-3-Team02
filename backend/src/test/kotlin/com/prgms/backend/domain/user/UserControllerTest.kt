package com.prgms.backend.domain.user

import com.prgms.backend.domain.user.controller.UserController
import com.prgms.backend.domain.user.dto.response.ProfileResponse
import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.service.AuthService
import com.prgms.backend.domain.user.service.UserService
import com.prgms.backend.domain.user.service.UserWithdrawService
import com.prgms.backend.global.exception.custom.user.DuplicateEmailNickname
import com.prgms.backend.global.exception.custom.user.PasswordMismatchException
import com.prgms.backend.security.JwtTokenProvider
import com.prgms.backend.support.WebSecurityTestConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// HTTP 매핑·JSON 형태·검증·인증 회원 id 전달
@WebMvcTest(
    controllers = [UserController::class],
    properties = [
        "custom.jwt.access-secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "custom.jwt.refresh-secret=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    ],
)
@Import(WebSecurityTestConfig::class)
internal class UserControllerTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @MockitoBean
    private lateinit var authService: AuthService

    @MockitoBean
    private lateinit var userService: UserService

    @MockitoBean
    private lateinit var userWithdrawService: UserWithdrawService

    @MockitoBean
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @MockitoBean
    private lateinit var userRepository: UserRepository

    // @EnableJpaAuditing이 메인 클래스에 있어 웹 슬라이스에서도 필요
    @MockitoBean
    private lateinit var jpaMetamodelMappingContext: JpaMetamodelMappingContext

    @Test
    fun `사용 중이지 않은 이메일이면 available이 true다`() {
        // given
        whenever(authService.checkEmail("new@test.com")).thenReturn(false)

        // when & then
        mvc
            .perform(get("/api/user/check-email").param("email", "new@test.com"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.available").value(true))
    }

    @Test
    fun `이미 사용 중인 이메일이면 available이 false다`() {
        // given
        whenever(authService.checkEmail("user@test.com")).thenReturn(true)

        // when & then
        mvc
            .perform(get("/api/user/check-email").param("email", "user@test.com"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.available").value(false))
    }

    @Test
    fun `이메일 형식이 아니면 이메일 중복 확인은 400이다`() {
        // when & then
        mvc
            .perform(get("/api/user/check-email").param("email", "not-email"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))

        verify(authService, never()).checkEmail(any())
    }

    @Test
    fun `닉네임 중복 확인은 사용 가능 여부를 반대로 응답한다`() {
        // given
        whenever(authService.checkNickname("회원")).thenReturn(true)

        // when & then
        mvc
            .perform(get("/api/user/check-nickname").param("nickname", "회원"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.available").value(false))
    }

    @Test
    fun `회원가입에 성공하면 201과 회원 id를 응답한다`() {
        // given
        whenever(authService.signup("new@test.com", "새회원", "Passw0rd!", "Passw0rd!"))
            .thenReturn(2L)

        // when & then
        mvc
            .perform(
                post("/api/user/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signUpJson("new@test.com", "새회원", "Passw0rd!", "Passw0rd!")),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value(201))
            .andExpect(jsonPath("$.data.userId").value(2))

        // 필드 순서가 바뀌어 전달되지 않는지 확인
        verify(authService).signup("new@test.com", "새회원", "Passw0rd!", "Passw0rd!")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Pass0!", "Password!", "12345678!", "Passw0rd1"])
    fun `비밀번호 형식 규칙을 지키지 않으면 회원가입은 400이다`(password: String) {
        // when & then
        mvc
            .perform(
                post("/api/user/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signUpJson("new@test.com", "새회원", password, password)),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value(PASSWORD_MESSAGE))

        verify(authService, never()).signup(any(), any(), any(), any())
    }

    @Test
    fun `이메일 형식이 아니면 회원가입은 400이다`() {
        // when & then
        mvc
            .perform(
                post("/api/user/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signUpJson("not-email", "새회원", "Passw0rd!", "Passw0rd!")),
            ).andExpect(status().isBadRequest())

        verify(authService, never()).signup(any(), any(), any(), any())
    }

    @Test
    fun `닉네임이 비어 있으면 회원가입은 400이다`() {
        // when & then
        mvc
            .perform(
                post("/api/user/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signUpJson("new@test.com", " ", "Passw0rd!", "Passw0rd!")),
            ).andExpect(status().isBadRequest())

        verify(authService, never()).signup(any(), any(), any(), any())
    }

    @Test
    fun `비밀번호 확인 필드가 없으면 회원가입은 400이다`() {
        // when & then
        mvc
            .perform(
                post("/api/user/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"new@test.com","nickname":"새회원","password":"Passw0rd!"}"""),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
    }

    @Test
    fun `이메일이나 닉네임이 중복이면 회원가입은 409다`() {
        // given
        whenever(authService.signup("user@test.com", "회원", "Passw0rd!", "Passw0rd!"))
            .thenThrow(DuplicateEmailNickname())

        // when & then
        mvc
            .perform(
                post("/api/user/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signUpJson("user@test.com", "회원", "Passw0rd!", "Passw0rd!")),
            ).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
    }

    @Test
    fun `내 프로필 조회는 토큰의 회원 id로 조회한다`() {
        // given
        givenAuthenticatedUser(1L)
        whenever(userService.getProfile(1L)).thenReturn(ProfileResponse("user@test.com", "회원"))

        // when & then
        mvc
            .perform(get("/api/user/me").header("Authorization", "Bearer $ACCESS_TOKEN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.email").value("user@test.com"))
            .andExpect(jsonPath("$.data.nickname").value("회원"))
    }

    @Test
    fun `닉네임을 변경하면 변경된 프로필을 응답한다`() {
        // given
        givenAuthenticatedUser(1L)
        whenever(userService.updateNickname(1L, "새닉네임"))
            .thenReturn(ProfileResponse("user@test.com", "새닉네임"))

        // when & then
        mvc
            .perform(
                patch("/api/user/me/nickname")
                    .header("Authorization", "Bearer $ACCESS_TOKEN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nickname":"새닉네임"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.nickname").value("새닉네임"))
    }

    @Test
    fun `빈 닉네임으로는 변경할 수 없다`() {
        // given
        givenAuthenticatedUser(1L)

        // when & then
        mvc
            .perform(
                patch("/api/user/me/nickname")
                    .header("Authorization", "Bearer $ACCESS_TOKEN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nickname":""}"""),
            ).andExpect(status().isBadRequest())

        verify(userService, never()).updateNickname(1L, "")
    }

    @Test
    fun `비밀번호를 변경하면 200과 안내 메시지를 응답한다`() {
        // given
        givenAuthenticatedUser(1L)

        // when & then
        mvc
            .perform(
                patch("/api/user/me/password")
                    .header("Authorization", "Bearer $ACCESS_TOKEN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"password":"Passw0rd!","newPassword":"NewPassw0rd!"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("비밀번호를 변경했습니다."))

        verify(userService).updatePassword(1L, "Passw0rd!", "NewPassw0rd!")
    }

    @Test
    fun `새 비밀번호가 형식 규칙을 지키지 않으면 400이다`() {
        // given
        givenAuthenticatedUser(1L)

        // when & then
        mvc
            .perform(
                patch("/api/user/me/password")
                    .header("Authorization", "Bearer $ACCESS_TOKEN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"password":"Passw0rd!","newPassword":"weak"}"""),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(PASSWORD_MESSAGE))

        verify(userService, never()).updatePassword(1L, "Passw0rd!", "weak")
    }

    @Test
    fun `현재 비밀번호가 틀리면 400이다`() {
        // given
        givenAuthenticatedUser(1L)
        doThrow(PasswordMismatchException())
            .whenever(userService)
            .updatePassword(1L, "wrong", "NewPassw0rd!")

        // when & then
        mvc
            .perform(
                patch("/api/user/me/password")
                    .header("Authorization", "Bearer $ACCESS_TOKEN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"password":"wrong","newPassword":"NewPassw0rd!"}"""),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("비밀번호가 일치하지 않습니다."))
    }

    @Test
    fun `회원 탈퇴하면 토큰의 회원 id로 탈퇴하고 refresh token 쿠키를 만료시킨다`() {
        // given
        givenAuthenticatedUser(1L)

        // when & then
        mvc
            .perform(delete("/api/user").header("Authorization", "Bearer $ACCESS_TOKEN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("회원 탈퇴가 완료되었습니다."))
            .andExpect(cookie().value("refreshToken", ""))
            .andExpect(cookie().maxAge("refreshToken", 0))
            .andExpect(cookie().path("refreshToken", "/api/auth"))

        verify(userWithdrawService).withdraw(1L)
    }

    private fun givenAuthenticatedUser(userId: Long) {
        whenever(jwtTokenProvider.validateAccessToken(ACCESS_TOKEN)).thenReturn(true)
        whenever(jwtTokenProvider.getAccessUserId(ACCESS_TOKEN)).thenReturn(userId)
        whenever(userRepository.existsByIdAndStatusAndDeletedAtIsNull(userId, UserStatus.ACTIVE))
            .thenReturn(true)
    }

    private fun signUpJson(
        email: String,
        nickname: String,
        password: String,
        confirmPassword: String,
    ): String =
        """{"email":"$email","nickname":"$nickname","password":"$password","confirmPassword":"$confirmPassword"}"""

    companion object {
        private const val ACCESS_TOKEN = "access-token"
        private const val PASSWORD_MESSAGE = "비밀번호는 영문, 숫자, 특수문자를 포함해 8자 이상이어야 합니다."
    }
}
