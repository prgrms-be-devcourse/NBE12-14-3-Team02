package com.prgms.backend.domain.schedule.poll.dto

import java.time.LocalDate

class ScheduleResultResponse private constructor() {
    data class Detail(
        val candidateRanks: List<CandidateRank>,
        val participantResponses: List<ScheduleParticipantResponse.MemberResponses>,
    )

    data class CandidateRank(
        val candidateId: Long,
        val candidateDate: LocalDate,
        val totalScore: Int,
        val rank: Int,
        val responseCount: Long,
        val nonResponseCount: Long,
        val preferenceCounts: PreferenceCounts,
    )

    data class PreferenceCounts(
        val prefer: Long,
        val available: Long,
        val dislike: Long,
        val impossible: Long,
    )
}
