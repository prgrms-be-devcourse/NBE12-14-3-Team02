package com.prgms.backend.domain.schedule.candidate.repository

import com.prgms.backend.domain.schedule.candidate.entity.ScheduleCandidate
import org.springframework.data.jpa.repository.JpaRepository

interface ScheduleCandidateRepository : JpaRepository<ScheduleCandidate, Long> {
    fun findByIdAndSchedulePollId(
        id: Long,
        schedulePollId: Long,
    ): ScheduleCandidate?
}
