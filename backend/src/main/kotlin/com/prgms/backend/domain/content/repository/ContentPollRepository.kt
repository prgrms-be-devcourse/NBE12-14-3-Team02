package com.prgms.backend.domain.content.repository

import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.meeting.enums.MeetingStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ContentPollRepository : JpaRepository<ContentPoll, Long> {
    fun findByMeetingId(meetingId: Long): ContentPoll?

    // 이 모임에 투표판이 존제는지에 대한 함수
    fun existsByMeetingId(meetingId: Long): Boolean

    // ACTIVE Meeting만 조회 -> 종료/삭제된 모임의 OPEN 투표판이 매 스케줄링마다 조회되는 것 방지
    @Query(
        """
        SELECT cp.id
        FROM ContentPoll cp
        JOIN Meeting m ON m.id = cp.meetingId
        WHERE cp.status = :pollStatus
          AND cp.deadline <= :now
          AND m.status = :meetingStatus
          AND m.deletedAt IS NULL
        """,
    )
    fun findExpiredIds(
        @Param("pollStatus") pollStatus: ContentPollStatus,
        @Param("meetingStatus") meetingStatus: MeetingStatus,
        @Param("now") now: LocalDateTime,
    ): List<Long>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT cp
        FROM ContentPoll cp
        WHERE cp.id = :pollId
        """,
    )
    fun findByIdForUpdate(
        @Param("pollId") pollId: Long,
    ): ContentPoll?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT cp
        FROM ContentPoll cp
        WHERE cp.meetingId = :meetingId
        """,
    )
    fun findByMeetingIdForUpdate(
        @Param("meetingId") meetingId: Long,
    ): ContentPoll?

    @Query(
        """
        SELECT cp.meetingId
        FROM ContentPoll cp
        WHERE cp.id = :pollId
        """,
    )
    fun findMeetingIdByPollId(
        @Param("pollId") pollId: Long,
    ): Long?

    fun existsByMeetingIdAndStatus(
        meetingId: Long,
        status: ContentPollStatus,
    ): Boolean
}
