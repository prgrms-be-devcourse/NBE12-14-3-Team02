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
        // 기존 응답 필드명(updateAd)을 그대로 유지한다. 오타 수정은 API 계약 변경이라 별도로 다룬다.
        val updateAd: LocalDateTime,
    ) {
        companion object {
            @JvmStatic
            fun from(contentVote: ContentVote): Saved =
                Saved(
                    voteId = contentVote.id,
                    candidateId = contentVote.contentCandidate.id,
                    meetingMemberId = contentVote.meetingMemberId,
                    preference = contentVote.preference,
                    createdAt = contentVote.createdAt,
                    updateAd = contentVote.updatedAt,
                )
        }
    }
}
