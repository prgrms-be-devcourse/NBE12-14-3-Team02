package com.prgms.backend.domain.schedule.vote.dto

import com.prgms.backend.domain.schedule.vote.entity.SchedulePreference
import com.prgms.backend.domain.schedule.vote.entity.ScheduleVote

class ScheduleVoteResponse private constructor() {
    data class Saved(
        val candidateId: Long,
        val meetingMemberId: Long,
        val preference: SchedulePreference,
    ) {
        companion object {
            fun from(vote: ScheduleVote): Saved =
                Saved(vote.scheduleCandidate.id, vote.meetingMember.id, vote.preference)
        }
    }
}
