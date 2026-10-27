package com.prgms.backend.global.config

import com.prgms.backend.domain.user.controller.AuthController
import com.prgms.backend.domain.user.controller.UserController
import com.prgms.backend.domain.user.dto.TokenPair
import com.prgms.backend.domain.user.dto.response.ProfileResponse
import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.service.AuthService
import com.prgms.backend.domain.user.service.UserService
import com.prgms.backend.domain.user.service.UserWithdrawService
import com.prgms.backend.security.JwtTokenProvider
import com.prgms.backend.support.WebSecurityTestConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// 공개/보호 경로, 인증 실패 응답 포맷, JWT 필터 연결
@WebMvcTest(
    controllers = [AuthController::class, UserController::class],
    properties = [
        "custom.jwt.access-secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "custom.jwt.refresh-secret=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    ],
)
@Import(WebSecurityTestConfig::class)
internal class SecurityConfigTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

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
    fun `회원가입·로그인·재발급·중복 확인은 토큰 없이 호출할 수 있다`() {
        // given

        whenever(authService.login(any()))
            .thenReturn(TokenPair("access", "refresh"))
        whenever(authService.reissue(anyOrNull()))
            .thenReturn(TokenPair("access", "refresh"))
        whenever(authService.signup("user@test.com", "회원", "Passw0rd!", "Passw0rd!"))
            .thenReturn(1L)

        // when & then
        mvc
            .perform(
                get("/api/user/check-email").param("email", "user@test.com"),
            ).andExpect(status().isOk())
        mvc
            .perform(
                get("/api/user/check-nickname").param("nickname", "회원"),
            ).andExpect(status().isOk())
        mvc
            .perform(
                post("/api/user/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email":"user@test.com","nickname":"회원","password":"Passw0rd!","confirmPassword":"Passw0rd!"}
                        """.trimIndent(),
                    ),
            ).andExpect(status().isCreated())
        mvc
            .perform(
                post("/api/auth/log-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email":"user@test.com","password":"Passw0rd!"}
                        """.trimIndent(),
                    ),
            ).andExpect(status().isCreated())
        mvc
            .perform(post("/api/auth/reissue"))
            .andExpect(status().isOk())
    }

    @Test
    fun `보호된 API를 토큰 없이 호출하면 401과 공통 에러 포맷으로 응답한다`() {
        // when & then

        mvc
            .perform(get("/api/user/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(
                content()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_JSON),
            ).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("인증이 필요합니다."))

        verifyNoInteractions(userService)
    }

    @Test
    fun `공개 목록에 없는 경로는 모두 인증이 필요하다`() {
        // when & then

        mvc
            .perform(get("/api/unknown"))
            .andExpect(status().isUnauthorized())
    }

    @Test
    fun `유효한 토큰과 활성 회원이면 보호된 API를 호출할 수 있다`() {
        // given

        givenValidToken(1L)
        whenever(
            userRepository.existsByIdAndStatusAndDeletedAtIsNull(
                1L,
                UserStatus.ACTIVE,
            ),
        ).thenReturn(true)
        whenever(userService.getProfile(1L))
            .thenReturn(ProfileResponse("user@test.com", "회원"))

        // when & then
        mvc
            .perform(
                get("/api/user/me")
                    .header("Authorization", "Bearer $ACCESS_TOKEN"),
            ).andExpect(status().isOk())

        verify(userService).getProfile(1L)
    }

    @Test
    fun `토큰 발급 이후 탈퇴한 회원은 유효한 토큰이 있어도 401이다`() {
        // given

        givenValidToken(1L)
        whenever(
            userRepository.existsByIdAndStatusAndDeletedAtIsNull(
                1L,
                UserStatus.ACTIVE,
            ),
        ).thenReturn(false)

        // when & then
        mvc
            .perform(
                get("/api/user/me")
                    .header("Authorization", "Bearer $ACCESS_TOKEN"),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))

        verifyNoInteractions(userService)
    }

    @Test
    fun `유효하지 않은 토큰이면 401이다`() {
        // given

        whenever(jwtTokenProvider.validateAccessToken("invalid-token"))
            .thenReturn(false)

        // when & then
        mvc
            .perform(
                get("/api/user/me")
                    .header("Authorization", "Bearer invalid-token"),
            ).andExpect(status().isUnauthorized())
    }

    @Test
    fun `Bearer 접두사가 없는 Authorization 헤더는 토큰으로 인정하지 않는다`() {
        // when & then

        mvc
            .perform(
                get("/api/user/me").header("Authorization", ACCESS_TOKEN),
            ).andExpect(status().isUnauthorized())

        verify(jwtTokenProvider, never())
            .validateAccessToken(any())
    }

    @Test
    fun `CSRF 토큰 없이도 인증된 POST 요청을 처리한다`() {
        // given

        givenValidToken(1L)
        whenever(
            userRepository.existsByIdAndStatusAndDeletedAtIsNull(
                1L,
                UserStatus.ACTIVE,
            ),
        ).thenReturn(true)

        // when & then
        mvc
            .perform(
                post("/api/auth/logout")
                    .header("Authorization", "Bearer $ACCESS_TOKEN"),
            ).andExpect(status().isOk())
    }

    @Test
    fun `같은 출처에서만 frame으로 띄울 수 있다`() {
        // when & then

        mvc
            .perform(
                get("/api/user/check-nickname").param("nickname", "회원"),
            ).andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
    }

    @Test
    fun `비밀번호는 BCrypt로 암호화한다`() {
        // when

        val encoded: String = checkNotNull(passwordEncoder.encode("Passw0rd!"))

        // then
        assertTrue(encoded.startsWith("$2"))
        assertTrue(passwordEncoder.matches("Passw0rd!", encoded))
    }

    private fun givenValidToken(userId: Long) {
        whenever(jwtTokenProvider.validateAccessToken(ACCESS_TOKEN))
            .thenReturn(true)
        whenever(jwtTokenProvider.getAccessUserId(ACCESS_TOKEN)).thenReturn(userId)
    }

    companion object {
        private const val ACCESS_TOKEN = "access-token"
    }
}
