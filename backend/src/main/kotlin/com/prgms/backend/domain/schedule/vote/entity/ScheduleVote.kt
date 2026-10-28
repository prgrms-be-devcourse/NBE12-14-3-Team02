package com.prgms.backend.domain.schedule.vote.entity

import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.schedule.candidate.entity.ScheduleCandidate
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
    name = "schedule_votes",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_vote_candidate_member",
            columnNames = ["schedule_candidate_id", "meeting_member_id"],
        ),
    ],
)
class ScheduleVote private constructor(
    scheduleCandidate: ScheduleCandidate,
    meetingMember: MeetingMember,
    preference: SchedulePreference,
) : BaseTimeEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_candidate_id", nullable = false)
    var scheduleCandidate: ScheduleCandidate = scheduleCandidate
        protected set

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_member_id", nullable = false)
    var meetingMember: MeetingMember = meetingMember
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var preference: SchedulePreference = preference
        protected set

    fun changePreference(preference: SchedulePreference) {
        this.preference = preference
    }

    fun getScore(): Int = preference.score

    companion object {
        fun create(
            scheduleCandidate: ScheduleCandidate,
            meetingMember: MeetingMember,
            preference: SchedulePreference,
        ): ScheduleVote = ScheduleVote(scheduleCandidate, meetingMember, preference)
    }
}
