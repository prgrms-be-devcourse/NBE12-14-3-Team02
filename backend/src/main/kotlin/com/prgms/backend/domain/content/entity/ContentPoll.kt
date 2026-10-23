package com.prgms.backend.domain.content.entity

import com.prgms.backend.domain.content.ENUM.ContentPollStatus
import com.prgms.backend.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "content_polls",
    // 한 모임에 ContentPoll은 하나만 들어올 수 있게
    uniqueConstraints = [UniqueConstraint(name = "uk_content_polls_meeting", columnNames = ["meeting_id"])],
)
class ContentPoll(
    meetingId: Long,
    deadline: LocalDateTime?,
) : BaseTimeEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @Column(name = "meeting_id", nullable = false)
    var meetingId: Long = meetingId
        protected set

    var deadline: LocalDateTime? = deadline
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ContentPollStatus = ContentPollStatus.OPEN
        protected set

    @Column(name = "confirmed_candidate_id")
    var confirmedCandidateId: Long? = null
        protected set

    fun changeDeadLine(deadLine: LocalDateTime?) {
        deadline = deadLine
    }

    fun close() {
        status = ContentPollStatus.CLOSED
    }

    fun confirm(candidateId: Long?) {
        confirmedCandidateId = candidateId
    }
}
