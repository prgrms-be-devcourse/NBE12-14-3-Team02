package com.prgms.backend.domain.meeting.entity

import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.user.entity.User
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
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "meeting_members",
    // 한 모임에 같은 user가 중복 가입하는 것을 방지한다.
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_meeting_member_meeting_user",
            columnNames = ["meeting_id", "user_id"],
        ),
    ],
)
class MeetingMember(
    meeting: Meeting,
    user: User,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "meeting_id", nullable = false)
    var meeting: Meeting = meeting
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "user_id", nullable = false)
    var user: User = user
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false)
    var status: MeetingMemberStatus = MeetingMemberStatus.JOINED
        protected set

    var joinedAt: LocalDateTime? = LocalDateTime.now()
        protected set

    var leftAt: LocalDateTime? = null
        protected set

    // 모임 탈퇴
    fun leave() {
        status = MeetingMemberStatus.LEFT
        leftAt = LocalDateTime.now()
    }

    // 모임 재가입
    fun rejoin() {
        status = MeetingMemberStatus.JOINED
        joinedAt = LocalDateTime.now()
        leftAt = null
    }

    // 모임에 가입되어 있는지 검사
    fun isJoined(): Boolean = status == MeetingMemberStatus.JOINED
}
