package com.prgms.backend.domain.content.repository

import com.prgms.backend.domain.content.entity.ContentCandidate
import org.springframework.data.jpa.repository.JpaRepository

interface ContentCandidateRepository : JpaRepository<ContentCandidate, Long> {
    // 동점일 경우는 먼저 등록한 후보순으로 리스트
    fun findByContentPollIdOrderByCreatedAtAsc(contentPollId: Long): List<ContentCandidate>

    // 같은 콘텐츠폴에서는 동일 제목 방지
    fun existsByContentPollIdAndTitle(
        contentPollId: Long,
        title: String,
    ): Boolean

    // 수정 시 제목 중복 검사 용
    fun existsByContentPollIdAndTitleAndIdNot(
        contentPollId: Long,
        title: String,
        id: Long,
    ): Boolean
}
