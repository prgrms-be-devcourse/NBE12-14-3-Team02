package com.prgms.backend.domain.content.dto.response

import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.enums.ContentPreference
import java.time.LocalDateTime

class ContentPollResponse private constructor() {
    data class Created(
        val id: Long,
        val meetingId: Long,
        val deadline: LocalDateTime?,
        val status: ContentPollStatus,
        val confirmedCandidateId: Long?,
    ) {
        companion object {
            fun from(contentPoll: ContentPoll): Created =
                Created(
                    id = contentPoll.id,
                    meetingId = contentPoll.meetingId,
                    deadline = contentPoll.deadline,
                    status = contentPoll.status,
                    confirmedCandidateId = contentPoll.confirmedCandidateId,
                )
        }
    }

    data class Detail(
        val id: Long,
        val meetingId: Long,
        val deadline: LocalDateTime?,
        val status: ContentPollStatus,
        val candidates: List<CandidateRank>,
    )

    data class CandidateRank(
        val candidateId: Long,
        val createdByMemberId: Long,
        val title: String,
        val description: String?,
        val totalScore: Int,
        val myPreference: ContentPreference?,
    )

    data class DeadlineUpdate(
        val id: Long,
        val deadline: LocalDateTime?,
        val status: ContentPollStatus,
        val confirmedCandidateId: Long?,
    ) {
        companion object {
            fun from(contentPoll: ContentPoll): DeadlineUpdate =
                DeadlineUpdate(
                    id = contentPoll.id,
                    deadline = contentPoll.deadline,
                    status = contentPoll.status,
                    confirmedCandidateId = contentPoll.confirmedCandidateId,
                )
        }
    }

    data class Confirmed(
        val id: Long,
        val meetingId: Long,
        val deadline: LocalDateTime?,
        val status: ContentPollStatus,
        val confirmedCandidateId: Long?,
    ) {
        companion object {
            fun from(contentPoll: ContentPoll): Confirmed =
                Confirmed(
                    id = contentPoll.id,
                    meetingId = contentPoll.meetingId,
                    deadline = contentPoll.deadline,
                    status = contentPoll.status,
                    confirmedCandidateId = contentPoll.confirmedCandidateId,
                )
        }
    }
}
