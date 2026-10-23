package com.prgms.backend.domain.content.repository

import com.prgms.backend.domain.content.entity.ContentVote
import com.prgms.backend.domain.content.enums.ContentPollStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ContentVoteRepository : JpaRepository<ContentVote, Long> {
    fun findByContentCandidateIdAndMeetingMemberId(
        contentCandidateId: Long,
        meetingMemberId: Long,
    ): ContentVote?

    fun findByContentCandidateId(contentCandidateId: Long): List<ContentVote>

    // 투표가 달린 후보 확인
    fun existsByContentCandidateId(contentCandidateId: Long): Boolean

    fun deleteByContentCandidateIdAndMeetingMemberId(
        contentCandidateId: Long,
        meetingMemberId: Long,
    )

    @Suppress("ktlint:standard:function-naming")
    fun findByContentCandidate_ContentPoll_Id(contentPollId: Long): List<ContentVote>

    @Modifying
    @Query(
        """
        DELETE FROM ContentVote cv
        WHERE cv.meetingMemberId = :meetingMemberId
          AND cv.contentCandidate.id IN (
              SELECT cc.id
              FROM ContentCandidate cc
              JOIN cc.contentPoll cp
              WHERE cp.meetingId = :meetingId
                AND cp.status = :status
                AND cp.deadline > :now
          )
        """,
    )
    fun deleteOpenVotesByMeetingMember(
        @Param("meetingId") meetingId: Long,
        @Param("meetingMemberId") meetingMemberId: Long,
        @Param("status") status: ContentPollStatus,
        @Param("now") now: LocalDateTime,
    ): Int
}
