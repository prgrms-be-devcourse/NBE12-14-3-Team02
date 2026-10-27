package com.prgms.backend.domain.user

import com.jayway.jsonpath.JsonPath
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.support.MySqlContainerSupport
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertNotNull

// 실제 빈(BCrypt·JWT·필터·트랜잭션·DB)으로 회원가입부터 탈퇴까지의 흐름 확인
@SpringBootTest
@AutoConfigureMockMvc
internal class UserAuthFlowIntegrationTest : MySqlContainerSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var email: String
    private lateinit var nickname: String

    @BeforeEach
    fun setUp() {
        val suffix = UUID.randomUUID().toString().substring(0, 8)
        email = "user-$suffix@test.com"
        nickname = "회원-$suffix"
    }

    @Test
    fun `회원가입하면 비밀번호를 BCrypt로 암호화해 활성 회원으로 저장한다`() {
        // when
        val userId = signUp()

        // then
        val user = findUser(userId)
        assertEquals(email, user.email)
        assertNotEquals(PASSWORD, user.password)
        assertTrue(passwordEncoder.matches(PASSWORD, user.password))
        assertEquals(UserStatus.ACTIVE, user.status)
    }

    @Test
    fun `가입 후 이메일과 닉네임 중복 확인은 사용 불가로 응답한다`() {
        // given
        signUp()

        // when & then
        mvc
            .perform(get("/api/user/check-email").param("email", email))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.available").value(false))
        mvc
            .perform(get("/api/user/check-nickname").param("nickname", nickname))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.available").value(false))
    }

    @Test
    fun `로그인으로 받은 access token으로 내 프로필을 조회하고 DB에 refresh token을 저장한다`() {
        // given
        val userId = signUp()

        // when
        val login = login(email, PASSWORD)

        // then
        val accessToken = accessToken(login)
        val refreshCookie = refreshCookie(login)
        assertEquals(refreshCookie.value, findUser(userId).refreshToken)

        mvc
            .perform(get("/api/user/me").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.data.nickname").value(nickname))
    }

    @Test
    fun `재발급한 refresh token을 DB에 저장하고 이전 쿠키의 재사용을 거부한다`() {
        // given
        val userId = signUp()
        val refreshCookie = refreshCookie(login(email, PASSWORD))

        // when
        val reissue =
            mvc
                .perform(post("/api/auth/reissue").cookie(refreshCookie))
                .andExpect(status().isOk())
                .andReturn()

        // then
        val newAccessToken = accessToken(reissue)
        val newRefreshCookie = refreshCookie(reissue)
        assertNotEquals(refreshCookie.value, newRefreshCookie.value)
        assertEquals(newRefreshCookie.value, findUser(userId).refreshToken)

        mvc
            .perform(post("/api/auth/reissue").cookie(refreshCookie))
            .andExpect(status().isUnauthorized())
        assertEquals(newRefreshCookie.value, findUser(userId).refreshToken)

        mvc
            .perform(get("/api/user/me").header("Authorization", "Bearer $newAccessToken"))
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(status().isOk())
        mvc
            .perform(post("/api/auth/reissue").cookie(newRefreshCookie))
            .andExpect(status().isOk())
    }

    @Test
    fun `access token을 refresh token 자리에 넣으면 재발급받을 수 없다`() {
        // given
        signUp()
        val accessToken = accessToken(login(email, PASSWORD))

        // when & then
        mvc
            .perform(post("/api/auth/reissue").cookie(Cookie("refreshToken", accessToken)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
    }

    @Test
    fun `로그아웃하면 DB의 refresh token이 지워져 같은 쿠키로 재발급받을 수 없다`() {
        // given
        val userId = signUp()
        val login = login(email, PASSWORD)
        val accessToken = accessToken(login)
        val refreshCookie = refreshCookie(login)

        // when
        mvc
            .perform(
                post("/api/auth/logout")
                    .header("Authorization", "Bearer $accessToken")
                    .cookie(refreshCookie),
            ).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("로그아웃 되었습니다."))
            .andExpect(jsonPath("$.data").doesNotExist())

        // then
        assertNull(findUser(userId).refreshToken)
        mvc
            .perform(post("/api/auth/reissue").cookie(refreshCookie))
            .andExpect(status().isUnauthorized())
    }

    @Test
    fun `비밀번호를 변경하면 새 비밀번호로만 로그인할 수 있다`() {
        // given
        signUp()
        val accessToken = accessToken(login(email, PASSWORD))

        // when
        mvc
            .perform(
                patch("/api/user/me/password")
                    .header("Authorization", "Bearer $accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"password":"$PASSWORD","newPassword":"NewPassw0rd!"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("비밀번호를 변경했습니다."))

        // then
        mvc
            .perform(
                post("/api/auth/log-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson(email, PASSWORD)),
            ).andExpect(status().isUnauthorized())
        login(email, "NewPassw0rd!")
    }

    @Test
    fun `닉네임을 변경하면 DB에 반영된다`() {
        // given
        val userId = signUp()
        val accessToken = accessToken(login(email, PASSWORD))
        val newNickname = "$nickname-new"

        // when
        mvc
            .perform(
                patch("/api/user/me/nickname")
                    .header("Authorization", "Bearer $accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nickname":"$newNickname"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.nickname").value(newNickname))

        // then
        assertEquals(newNickname, findUser(userId).nickname)
    }

    @Test
    fun `탈퇴하면 기존 access token과 refresh token을 쓸 수 없고 다시 로그인할 수 없다`() {
        // given
        val userId = signUp()
        val login = login(email, PASSWORD)
        val accessToken = accessToken(login)
        val refreshCookie = refreshCookie(login)

        // when
        mvc
            .perform(delete("/api/user").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("회원 탈퇴가 완료되었습니다."))

        // then
        val user = findUser(userId)
        assertEquals(UserStatus.WITHDRAWN, user.status)
        assertNotNull(user.deletedAt)
        assertNull(user.refreshToken)

        mvc
            .perform(get("/api/user/me").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isUnauthorized())
        mvc
            .perform(post("/api/auth/reissue").cookie(refreshCookie))
            .andExpect(status().isUnauthorized())
        mvc
            .perform(
                post("/api/auth/log-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson(email, PASSWORD)),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("탈퇴한 회원입니다."))
    }

    private fun signUp(): Long {
        val result =
            mvc
                .perform(
                    post("/api/user/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"email":"$email","nickname":"$nickname","password":"$PASSWORD","confirmPassword":"$PASSWORD"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()

        val userId: Number = JsonPath.read(result.response.contentAsString, "$.data.userId")
        return userId.toLong()
    }

    private fun login(
        email: String,
        password: String,
    ): MvcResult =
        mvc
            .perform(
                post("/api/auth/log-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson(email, password)),
            ).andExpect(status().isCreated())
            .andReturn()

    private fun loginJson(
        email: String,
        password: String,
    ): String = """{"email":"$email","password":"$password"}"""

    private fun accessToken(result: MvcResult): String =
        JsonPath.read(result.response.contentAsString, "$.data.accessToken")

    private fun refreshCookie(result: MvcResult): Cookie = assertNotNull(result.response.getCookie("refreshToken"))

    private fun findUser(userId: Long): User = assertNotNull(userRepository.findByIdOrNull(userId))

    companion object {
        private const val PASSWORD = "Passw0rd!"
    }
}
