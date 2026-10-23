package com.prgms.backend.domain.meeting.entity

import com.prgms.backend.global.entity.BaseCreatedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "meeting_invitations")
class MeetingInvitation(
    meeting: Meeting,
    inviteCode: String,
    expiresAt: LocalDateTime,
) : BaseCreatedEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "meeting_id", nullable = false)
    var meeting: Meeting = meeting
        protected set

    // 모임 별 초대 코드는 유일해야 하므로 unique
    // 초대 url은 프론트에서 inviteCode를 사용해 생성하므로 엔티티에 선언X
    @field:Column(name = "invite_code", nullable = false, unique = true)
    var inviteCode: String = inviteCode
        protected set

    // 초대 코드 만료 시점
    @field:Column(name = "expires_at", nullable = false)
    var expiresAt: LocalDateTime = expiresAt
        protected set

    // 초대가 만료됐는지 검사
    fun isExpired(): Boolean = expiresAt.isBefore(LocalDateTime.now())
}
