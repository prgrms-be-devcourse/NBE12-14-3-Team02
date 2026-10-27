package com.prgms.backend.domain.user

import com.prgms.backend.domain.user.controller.AuthController
import com.prgms.backend.domain.user.dto.TokenPair
import com.prgms.backend.domain.user.dto.request.LogInRequest
import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.service.AuthService
import com.prgms.backend.global.exception.custom.user.LoginFailException
import com.prgms.backend.global.exception.custom.user.ReissueFailException
import com.prgms.backend.security.JwtTokenProvider
import com.prgms.backend.support.WebSecurityTestConfig
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// HTTP 매핑·JSON 형태·검증·refresh token 쿠키 속성
@WebMvcTest(
    controllers = [AuthController::class],
    properties = [
        "custom.jwt.access-secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "custom.jwt.refresh-secret=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    ],
)
@Import(WebSecurityTestConfig::class)
internal class AuthControllerTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @MockitoBean
    private lateinit var authService: AuthService

    @MockitoBean
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @MockitoBean
    private lateinit var userRepository: UserRepository

    // @EnableJpaAuditing이 메인 클래스에 있어 웹 슬라이스에서도 필요
    @MockitoBean
    private lateinit var jpaMetamodelMappingContext: JpaMetamodelMappingContext

    @Test
    fun `로그인에 성공하면 201과 access token을 응답하고 refresh token은 쿠키로만 내려준다`() {
        // given
        whenever(authService.login(LogInRequest("user@test.com", "Passw0rd!")))
            .thenReturn(TokenPair("new-access-token", "new-refresh-token"))

        // when & then
        mvc
            .perform(
                post("/api/auth/log-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"user@test.com","password":"Passw0rd!"}"""),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value(201))
            .andExpect(jsonPath("$.message").value(""))
            .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andExpect(cookie().value("refreshToken", "new-refresh-token"))
            .andExpect(cookie().httpOnly("refreshToken", true))
            .andExpect(cookie().secure("refreshToken", true))
            .andExpect(cookie().sameSite("refreshToken", "Strict"))
            .andExpect(cookie().path("refreshToken", "/api/auth"))
            .andExpect(cookie().maxAge("refreshToken", 14 * 24 * 60 * 60))
    }

    @Test
    fun `이메일 형식이 아니면 로그인 요청은 400이다`() {
        // when & then
        mvc
            .perform(
                post("/api/auth/log-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"not-email","password":"Passw0rd!"}"""),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(400))

        verify(authService, never()).login(any())
    }

    @Test
    fun `비밀번호 필드가 없으면 로그인 요청은 400이다`() {
        // when & then
        mvc
            .perform(
                post("/api/auth/log-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"user@test.com"}"""),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))

        verify(authService, never()).login(any())
    }

    @Test
    fun `로그인 실패 예외는 401과 공통 에러 포맷으로 응답한다`() {
        // given
        whenever(authService.login(any())).thenThrow(LoginFailException())

        // when & then
        mvc
            .perform(
                post("/api/auth/log-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"user@test.com","password":"wrong"}"""),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 일치하지 않습니다."))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    fun `refresh token 쿠키로 재발급하면 새 access token과 새 refresh token 쿠키를 내려준다`() {
        // given
        whenever(authService.reissue("old-refresh-token"))
            .thenReturn(TokenPair("new-access-token", "new-refresh-token"))

        // when & then
        mvc
            .perform(
                post("/api/auth/reissue")
                    .cookie(Cookie("refreshToken", "old-refresh-token")),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
            .andExpect(cookie().value("refreshToken", "new-refresh-token"))
            .andExpect(cookie().httpOnly("refreshToken", true))
            .andExpect(cookie().path("refreshToken", "/api/auth"))
    }

    @Test
    fun `refresh token 쿠키 없이 재발급을 요청하면 null을 넘겨 401로 응답한다`() {
        // given
        whenever(authService.reissue(null)).thenThrow(ReissueFailException())

        // when & then
        mvc
            .perform(post("/api/auth/reissue"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("reissue에 실패했습니다."))

        verify(authService).reissue(null)
    }

    @Test
    fun `로그아웃하면 200과 공통 응답 본문을 반환하고 refresh token 쿠키를 만료시킨다`() {
        // given
        givenAuthenticatedUser(1L)

        // when & then
        mvc
            .perform(
                post("/api/auth/logout")
                    .header("Authorization", "Bearer $ACCESS_TOKEN")
                    .cookie(Cookie("refreshToken", "refresh-token")),
            ).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("로그아웃 되었습니다."))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(cookie().value("refreshToken", ""))
            .andExpect(cookie().maxAge("refreshToken", 0))
            .andExpect(cookie().path("refreshToken", "/api/auth"))

        verify(authService).logout(1L, "refresh-token")
    }

    @Test
    fun `access token 없이 로그아웃하면 401이다`() {
        // when & then
        mvc
            .perform(post("/api/auth/logout"))
            .andExpect(status().isUnauthorized())

        verify(authService, never()).logout(any(), anyOrNull())
    }

    private fun givenAuthenticatedUser(userId: Long) {
        whenever(jwtTokenProvider.validateAccessToken(ACCESS_TOKEN)).thenReturn(true)
        whenever(jwtTokenProvider.getAccessUserId(ACCESS_TOKEN)).thenReturn(userId)
        whenever(userRepository.existsByIdAndStatusAndDeletedAtIsNull(userId, UserStatus.ACTIVE))
            .thenReturn(true)
    }

    companion object {
        private const val ACCESS_TOKEN = "access-token"
    }
}
