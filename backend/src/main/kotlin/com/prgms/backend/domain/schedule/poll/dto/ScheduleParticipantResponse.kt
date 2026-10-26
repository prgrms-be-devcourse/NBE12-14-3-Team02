package com.prgms.backend.domain.schedule.poll.dto

import com.prgms.backend.domain.schedule.vote.entity.SchedulePreference
import java.time.LocalDate

class ScheduleParticipantResponse private constructor() {
    data class MemberResponses(
        val meetingMemberId: Long,
        val userId: Long,
        val nickname: String,
        val answers: List<CandidateAnswer>,
    )

    data class CandidateAnswer(
        val candidateId: Long,
        val candidateDate: LocalDate,
        val preference: SchedulePreference?,
    )
}
