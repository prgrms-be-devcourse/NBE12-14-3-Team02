package com.prgms.backend.domain.content.dto.response

import com.prgms.backend.domain.content.entity.ContentCandidate
import java.time.LocalDateTime

class ContentCandidateResponse private constructor() {
    data class Saved(
        val candidateId: Long,
        val contentPollId: Long,
        val createdByMemberId: Long,
        val title: String,
        val description: String?,
        val createdAt: LocalDateTime,
    ) {
        companion object {
            @JvmStatic
            fun from(candidate: ContentCandidate): Saved =
                Saved(
                    candidateId = candidate.id,
                    contentPollId = candidate.contentPoll.id,
                    createdByMemberId = candidate.createdByMemberId,
                    title = candidate.title,
                    description = candidate.description,
                    createdAt = candidate.createdAt,
                )
        }
    }
}
