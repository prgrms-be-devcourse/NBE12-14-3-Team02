package com.prgms.backend.domain.schedule.candidate.dto

import com.prgms.backend.domain.schedule.candidate.entity.ScheduleCandidate
import java.time.LocalDate

class ScheduleCandidateResponse private constructor() {
    data class Summary(
        val id: Long,
        val candidateDate: LocalDate,
    ) {
        companion object {
            fun from(candidate: ScheduleCandidate): Summary = Summary(candidate.id, candidate.candidateDate)
        }
    }
}
