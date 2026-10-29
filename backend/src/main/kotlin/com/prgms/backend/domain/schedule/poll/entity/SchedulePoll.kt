package com.prgms.backend.domain.schedule.poll.entity

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.schedule.candidate.entity.ScheduleCandidate
import com.prgms.backend.global.exception.custom.schedule.ScheduleCandidateLimitExceededException
import com.prgms.backend.global.exception.custom.schedule.ScheduleCandidateNotFoundException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollClosedException
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "schedule_polls")
class SchedulePoll private constructor(
    meeting: Meeting,
    deadline: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false, unique = true)
    var meeting: Meeting = meeting
        protected set

    @Column(nullable = false)
    var deadline: LocalDateTime = deadline
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: SchedulePollStatus = SchedulePollStatus.OPEN
        protected set

    @OneToMany(mappedBy = "schedulePoll", cascade = [CascadeType.ALL], orphanRemoval = true)
    private var candidates: MutableList<ScheduleCandidate> = mutableListOf()

    fun getCandidates(): List<ScheduleCandidate> = candidates.toList()

    fun addCandidate(candidateDate: LocalDate): ScheduleCandidate {
        if (candidates.size >= MAX_CANDIDATE_COUNT) {
            throw ScheduleCandidateLimitExceededException(MAX_CANDIDATE_COUNT)
        }
        val candidate = ScheduleCandidate.create(this, candidateDate)
        candidates.add(candidate)
        return candidate
    }

    fun updateDeadline(
        newDeadline: LocalDateTime,
        now: LocalDateTime,
    ) {
        validateOpen(now)
        deadline = newDeadline
    }

    fun validateOpen(now: LocalDateTime) {
        if (status == SchedulePollStatus.CLOSED || !now.isBefore(deadline)) {
            throw SchedulePollClosedException()
        }
    }

    fun findCandidate(candidateId: Long): ScheduleCandidate =
        candidates.firstOrNull { it.id == candidateId }
            ?: throw ScheduleCandidateNotFoundException(candidateId)

    fun hasDuplicateDate(
        excludedCandidateId: Long,
        candidateDate: LocalDate,
    ): Boolean = candidates.any { it.id != excludedCandidateId && it.candidateDate == candidateDate }

    fun hasDuplicateDate(candidateDate: LocalDate): Boolean = candidates.any { it.candidateDate == candidateDate }

    fun removeCandidate(candidate: ScheduleCandidate) {
        candidates.remove(candidate)
    }

    fun close() {
        status = SchedulePollStatus.CLOSED
    }

    companion object {
        private const val MAX_CANDIDATE_COUNT = 10

        fun create(
            meeting: Meeting,
            deadline: LocalDateTime,
        ): SchedulePoll = SchedulePoll(meeting, deadline)
    }
}
