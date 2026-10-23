package com.prgms.backend.domain.user.repository

import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.enums.UserStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface UserRepository : JpaRepository<User, Long> {

    fun existsByEmail(email: String): Boolean
    fun existsByNickname(nickname: String): Boolean
    fun existsByIdAndStatusAndDeletedAtIsNull(id: Long, status: UserStatus): Boolean
    fun findByEmail(email: String): Optional<User>
}
