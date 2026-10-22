package com.prgms.backend.global.config

import com.prgms.backend.global.ApiResponse
import com.prgms.backend.security.JwtAuthenticationFilter
import io.jsonwebtoken.security.Keys
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

@Configuration
class SecurityConfig(
    private val jsonMapper: JsonMapper,
) {
    @Bean
    fun filterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
    ): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/api/user/sign-up", permitAll)
                authorize("/api/auth/log-in", permitAll)
                authorize("/api/auth/reissue", permitAll)
                authorize("/api/user/check-nickname", permitAll)
                authorize("/api/user/check-email", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            csrf { disable() }
            headers {
                addHeaderWriter(
                    XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.SAMEORIGIN),
                )
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(jwtAuthenticationFilter)
            exceptionHandling {
                authenticationEntryPoint =
                    AuthenticationEntryPoint { _, response, _ ->
                        writeError(response, 401, "인증이 필요합니다.")
                    }
                accessDeniedHandler =
                    AccessDeniedHandler { _, response, _ ->
                        writeError(response, 403, "접근 권한이 없습니다.")
                    }
            }
        }
        return http.build()
    }

    private fun writeError(
        response: HttpServletResponse,
        status: Int,
        message: String,
    ) {
        response.status = status
        response.contentType = "application/json;charset=UTF-8"
        jsonMapper.writeValue(response.writer, ApiResponse.error(status, message))
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun accessSecretKey(
        @Value("\${custom.jwt.access-secret}") secret: String,
    ): SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))

    @Bean
    fun refreshSecretKey(
        @Value("\${custom.jwt.refresh-secret}") secret: String,
    ): SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
}
