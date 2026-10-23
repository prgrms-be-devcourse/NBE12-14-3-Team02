package com.prgms.backend.domain.user.service

import com.prgms.backend.domain.user.dto.response.ProfileResponse
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.repository.getUser
import com.prgms.backend.global.exception.custom.user.DuplicateEmailNickname
import com.prgms.backend.global.exception.custom.user.PasswordMismatchException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authService: AuthService,
) {
    // 프로필 조회
    fun getProfile(userId: Long): ProfileResponse {
        val user = userRepository.getUser(userId)

        return ProfileResponse(user.email, user.nickname)
    }

    // 닉네임 수정
    @Transactional
    fun updateNickname(
        userId: Long,
        nickname: String,
    ): ProfileResponse {
        // 새로운 닉네임이 사용 중인지 확인
        if (authService.checkNickname(nickname)) {
            throw DuplicateEmailNickname()
        }

        val user = userRepository.getUser(userId)

        user.updateNickname(nickname)
        return ProfileResponse(user.email, user.nickname)
    }

    // 비밀번호 수정
    @Transactional
    fun updatePassword(
        userId: Long,
        password: String,
        newPassword: String,
    ) {
        val user = userRepository.getUser(userId)

        // 비밀번호가 일치하는지 확인
        if (!passwordEncoder.matches(password, user.password)) {
            throw PasswordMismatchException()
        }

        // 암호화한 비밀번호로 업데이트
        val encodedPassword = checkNotNull(passwordEncoder.encode(newPassword)) { "비밀번호 암호화 결과가 없습니다." }
        user.updatePassword(encodedPassword)
    }
}
