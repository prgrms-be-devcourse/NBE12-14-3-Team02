package com.prgms.backend.global.config

import com.prgms.backend.support.MySqlContainerSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// API 문서 공개 여부, JWT 보안 스키마, 공개 API의 인증 해제 표시
@SpringBootTest
@AutoConfigureMockMvc
internal class SwaggerConfigTest : MySqlContainerSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    fun `API 문서는 토큰 없이 조회할 수 있고 JWT bearer 보안 스키마를 전역으로 적용한다`() {
        // when & then

        mvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.info.title").value("모임 일정 조율 · 정산 서비스 API"),
            ).andExpect(jsonPath("$.info.version").value("v1.0"))
            .andExpect(
                jsonPath("$.components.securitySchemes.bearerAuth.type")
                    .value("http"),
            ).andExpect(
                jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                    .value("bearer"),
            ).andExpect(
                jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat")
                    .value("JWT"),
            ).andExpect(jsonPath("$.security[0].bearerAuth").isArray())
    }

    @Test
    fun `공개 API는 문서에서 인증 요구를 해제하고 보호된 API는 전역 인증을 따른다`() {
        // when & then

        mvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.paths['/api/auth/log-in'].post.security")
                    .isEmpty(),
            ).andExpect(
                jsonPath("$.paths['/api/auth/reissue'].post.security")
                    .isEmpty(),
            ).andExpect(
                jsonPath("$.paths['/api/user/sign-up'].post.security")
                    .isEmpty(),
            ).andExpect(
                jsonPath("$.paths['/api/user/check-email'].get.security")
                    .isEmpty(),
            ).andExpect(
                jsonPath("$.paths['/api/user/check-nickname'].get.security")
                    .isEmpty(),
            ).andExpect(
                jsonPath("$.paths['/api/user/me'].get.security")
                    .doesNotExist(),
            ).andExpect(
                jsonPath("$.paths['/api/auth/logout'].post.security")
                    .doesNotExist(),
            )
    }

    @Test
    fun `요청 DTO의 예시와 비밀번호 패턴이 문서에 노출된다`() {
        // when & then

        mvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.components.schemas.SignUpRequest.properties.email.example")
                    .value("test@example.com"),
            ).andExpect(
                jsonPath("$.components.schemas.SignUpRequest.properties.password.pattern")
                    .value("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=]).{8,}$"),
            ).andExpect(
                jsonPath("$.components.schemas.LogInRequest.required")
                    .isArray(),
            )
    }

    @Test
    fun `Swagger UI는 토큰 없이 접근할 수 있다`() {
        // when & then

        mvc
            .perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk())
    }
}
