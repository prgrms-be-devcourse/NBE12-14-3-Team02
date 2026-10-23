package com.prgms.backend.domain.content.dto.response

import com.prgms.backend.domain.content.entity.ContentVote
import com.prgms.backend.domain.content.enums.ContentPreference
import java.time.LocalDateTime

class ContentVoteResponse private constructor() {
    data class Saved(
        val voteId: Long,
        val candidateId: Long,
        val meetingMemberId: Long,
        val preference: ContentPreference,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
    ) {
        companion object {
            fun from(contentVote: ContentVote): Saved =
                Saved(
                    voteId = contentVote.id,
                    candidateId = contentVote.contentCandidate.id,
                    meetingMemberId = contentVote.meetingMemberId,
                    preference = contentVote.preference,
                    createdAt = contentVote.createdAt,
                    updatedAt = contentVote.updatedAt,
                )
        }
    }
}
