package com.prgms.backend.domain.meeting.entity

import com.prgms.backend.domain.meeting.enums.MeetingStatus
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "meetings")
class Meeting(
    host: User,
    name: String,
    description: String?,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "host_id", nullable = false)
    var host: User = host
        protected set

    @field:Column(nullable = false, length = 100)
    var name: String = name
        protected set

    @field:Column(length = 500)
    var description: String? = description
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false)
    var status: MeetingStatus = MeetingStatus.ACTIVE
        protected set

    var deletedAt: LocalDateTime? = null
        protected set

    // 이 모임에서 특정 모임원이 모임장인지 검사
    fun isHost(userId: Long?): Boolean = host.id == userId

    // 모임 수정
    fun update(
        name: String,
        description: String?,
    ) {
        this.name = name
        this.description = description
    }

    // 정상적인 모임 종료
    fun complete() {
        status = MeetingStatus.COMPLETED
    }

    // soft delete
    fun softDelete() {
        deletedAt = LocalDateTime.now()
    }

    // 진행 중인 모임인지
    fun isActive(): Boolean = status == MeetingStatus.ACTIVE && deletedAt == null

    // 종료된 모임인지
    fun isCompleted(): Boolean = status == MeetingStatus.COMPLETED && deletedAt == null

    // soft delete된 모임인지
    fun isDeleted(): Boolean = deletedAt != null
}
