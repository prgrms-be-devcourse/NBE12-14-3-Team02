package com.prgms.backend.domain.content.entity

import com.prgms.backend.domain.content.ENUM.ContentPreference
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
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "content_votes",
    // 한 멤버가 한 후보에게 여러 표를 못 붙이게
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_content_votes_candidate_member",
            columnNames = ["content_candidate_id", "meeting_member_id"],
        ),
    ],
)
class ContentVote(
    contentCandidate: ContentCandidate,
    meetingMemberId: Long,
    preference: ContentPreference,
) : BaseTimeEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_candidate_id", nullable = false)
    var contentCandidate: ContentCandidate = contentCandidate
        protected set

    @Column(name = "meeting_member_id", nullable = false)
    var meetingMemberId: Long = meetingMemberId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var preference: ContentPreference = preference
        protected set

    fun changePreference(preference: ContentPreference) {
        this.preference = preference
    }
}
