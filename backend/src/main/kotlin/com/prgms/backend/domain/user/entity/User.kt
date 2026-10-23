package com.prgms.backend.domain.user.entity

import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "users")
class User(
    email: String,
    nickname: String,
    password: String,
) : BaseTimeEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @Column(nullable = false, unique = true)
    var email: String = email
        protected set

    @Column(nullable = false, unique = true)
    var nickname: String = nickname
        protected set

    @Column(nullable = false)
    var password: String = password
        protected set

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: UserStatus = UserStatus.ACTIVE
        protected set

    var deletedAt: LocalDateTime? = null
        protected set

    var refreshToken: String? = null
        protected set

    fun updateRefreshToken(refreshToken: String?) {
        this.refreshToken = refreshToken
    }

    fun updateNickname(nickname: String) {
        this.nickname = nickname
    }

    fun updatePassword(password: String) {
        this.password = password
    }

    fun updateStatus(status: UserStatus) {
        this.status = status
    }

    fun updateDeletedAt(deletedAt: LocalDateTime) {
        this.deletedAt = deletedAt
    }
}
