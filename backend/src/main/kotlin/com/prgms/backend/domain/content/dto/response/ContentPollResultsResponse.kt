package com.prgms.backend.domain.content.dto.response

import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.enums.ContentPreference
import java.time.LocalDateTime

class ContentPollResultsResponse private constructor() {
    data class Detail(
        val id: Long,
        val meetingId: Long,
        val deadline: LocalDateTime?,
        val status: ContentPollStatus,
        val confirmedCandidateId: Long?,
        val joinedCount: Int,
        val candidates: List<CandidateResult>,
        val members: List<MemberResult>,
    )

    data class CandidateResult(
        val candidateId: Long,
        val createdByMemberId: Long,
        val createdByNickname: String?,
        val title: String,
        val description: String?,
        val totalScore: Int,
        val preferCount: Int,
        val availableCount: Int,
        val dislikeCount: Int,
        val responseCount: Int,
        val noResponseCount: Int,
    )

    data class MemberResult(
        val meetingMemberId: Long,
        val userId: Long,
        val nickname: String,
        val host: Boolean,
        // 투표하지 않은 후보 자리는 null이다.
        val preferences: List<ContentPreference?>,
        val responseCount: Int,
    )
}
