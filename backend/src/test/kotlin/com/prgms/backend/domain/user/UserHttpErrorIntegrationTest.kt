package com.prgms.backend.domain.user

import com.jayway.jsonpath.JsonPath
import com.prgms.backend.support.MySqlContainerSupport
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.emptyOrNullString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
internal class UserHttpErrorIntegrationTest : MySqlContainerSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @ParameterizedTest(name = "{0}")
    @MethodSource("badRequests")
    fun `잘못된 요청은 500 대신 400과 공통 오류 형식으로 응답한다`(
        description: String,
        request: RequestBuilder,
    ) {
        mvc
            .perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message", not(emptyOrNullString())))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    fun `지원하지 않는 HTTP 메서드는 405와 공통 오류 형식 및 Allow 헤더로 응답한다`() {
        mvc
            .perform(post("/api/user/check-email").param("email", "user@test.com"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(405))
            .andExpect(jsonPath("$.message", not(emptyOrNullString())))
            .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
    }

    @Test
    fun `지원하지 않는 Content-Type은 415와 공통 오류 형식으로 응답한다`() {
        mvc
            .perform(post("/api/user/sign-up").contentType(MediaType.TEXT_PLAIN).content("text"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(415))
            .andExpect(jsonPath("$.message", not(emptyOrNullString())))
    }

    @Test
    fun `인증된 사용자가 존재하지 않는 API를 요청하면 404와 공통 오류 형식으로 응답한다`() {
        val suffix = UUID.randomUUID().toString()
        val email = "$suffix@test.com"
        mvc
            .perform(
                post("/api/user/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"email":"$email","nickname":"$suffix","password":"Passw0rd!","confirmPassword":"Passw0rd!"}""",
                    ),
            ).andExpect(status().isCreated())
        val loginBody =
            mvc
                .perform(
                    post("/api/auth/log-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"Passw0rd!"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        val accessToken: String = JsonPath.read(loginBody, "$.data.accessToken")
        mvc
            .perform(get("/api/user/me").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
            .andExpect(status().isOk())

        mvc
            .perform(
                get("/api/user/not-existing-endpoint")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message", not(emptyOrNullString())))
    }

    companion object {
        @JvmStatic
        fun badRequests(): List<Arguments> =
            listOf(
                Arguments.of("이메일 중복 확인의 필수 파라미터 누락", get("/api/user/check-email")),
                Arguments.of("닉네임 중복 확인의 필수 파라미터 누락", get("/api/user/check-nickname")),
                Arguments.of(
                    "회원가입 JSON 문법 오류",
                    post("/api/user/sign-up").contentType(MediaType.APPLICATION_JSON).content("{"),
                ),
                Arguments.of(
                    "로그인 요청 본문 누락",
                    post("/api/auth/log-in").contentType(MediaType.APPLICATION_JSON),
                ),
                Arguments.of(
                    "회원가입 본문에 객체 대신 배열 전달",
                    post("/api/user/sign-up").contentType(MediaType.APPLICATION_JSON).content("[]"),
                ),
            )
    }
}
