package com.prgms.backend.domain.schedule.candidate.entity

import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
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
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

@Entity
@Table(
    name = "schedule_candidates",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_candidate_poll_date",
            columnNames = ["schedule_poll_id", "candidate_date"],
        ),
    ],
)
class ScheduleCandidate private constructor(
    schedulePoll: SchedulePoll,
    candidateDate: LocalDate,
) : BaseCreatedEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_poll_id", nullable = false)
    var schedulePoll: SchedulePoll = schedulePoll
        protected set

    @Column(name = "candidate_date", nullable = false)
    var candidateDate: LocalDate = candidateDate
        protected set

    fun updateCandidateDate(candidateDate: LocalDate) {
        this.candidateDate = candidateDate
    }

    companion object {
        @JvmStatic
        fun create(
            schedulePoll: SchedulePoll,
            candidateDate: LocalDate,
        ): ScheduleCandidate = ScheduleCandidate(schedulePoll, candidateDate)
    }
}
