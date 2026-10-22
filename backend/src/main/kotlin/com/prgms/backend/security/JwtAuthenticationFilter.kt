package com.prgms.backend.security

import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.domain.user.repository.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val userRepository: UserRepository,
) : OncePerRequestFilter() {
    // 토큰 분리
    private fun resolveToken(request: HttpServletRequest): String? {
        val bearerToken: String? = request.getHeader("Authorization")
        return if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken.substring(7)
        } else {
            null
        }
    }

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = resolveToken(request)

        if (token != null && jwtTokenProvider.validateAccessToken(token)) {
            // 토큰이 유효한 경우 인증 정보를 가져온다.
            val userId = jwtTokenProvider.getAccessUserId(token)

            // 발급 이후 탈퇴한 회원도 차단하도록 현재 상태를 확인한다.
            val activeUser: Boolean = userRepository.existsByIdAndStatusAndDeletedAtIsNull(userId, UserStatus.ACTIVE)

            if (activeUser) {
                val securityUser = SecurityUser(userId)
                val authentication: Authentication =
                    UsernamePasswordAuthenticationToken(
                        securityUser,
                        null,
                        securityUser.authorities,
                    )
                SecurityContextHolder.getContext().authentication = authentication
            } else {
                // 보호된 API의 401 응답은 SecurityConfig의 인증 진입점에서 처리한다.
                SecurityContextHolder.clearContext()
            }
        }

        filterChain.doFilter(request, response)
    }
}
