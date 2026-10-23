package com.prgms.backend.domain.user.repository

import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.global.exception.custom.user.UserNotFoundException
import org.springframework.data.repository.findByIdOrNull

fun UserRepository.getUser(
    userId: Long,
    message: String = "회원 정보가 존재하지 않습니다.",
): User = findByIdOrNull(userId) ?: throw UserNotFoundException(message)
