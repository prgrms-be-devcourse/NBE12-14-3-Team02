package com.prgms.backend.domain.schedule.poll.repository

import com.prgms.backend.domain.meeting.enums.MeetingStatus
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface SchedulePollRepository : JpaRepository<SchedulePoll, Long> {
    fun existsByMeetingId(meetingId: Long): Boolean

    fun findByMeetingId(meetingId: Long): SchedulePoll?

    fun existsByMeetingIdAndStatus(
        meetingId: Long,
        status: SchedulePollStatus,
    ): Boolean

    @Query(
        """
        SELECT DISTINCT sp
        FROM SchedulePoll sp
        LEFT JOIN FETCH sp.candidates
        WHERE sp.meeting.id = :meetingId
        """,
    )
    fun findByMeetingIdWithCandidates(
        @Param("meetingId") meetingId: Long,
    ): SchedulePoll?

    // 조회 시 락 획득
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT sp
        FROM SchedulePoll sp
        WHERE sp.meeting.id = :meetingId
        """,
    )
    fun findByMeetingIdForUpdate(
        @Param("meetingId") meetingId: Long,
    ): SchedulePoll?

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query(
        """
        SELECT sp
        FROM SchedulePoll sp
        WHERE sp.meeting.id = :meetingId
        """,
    )
    fun findByMeetingIdForShare(
        @Param("meetingId") meetingId: Long,
    ): SchedulePoll?

    @Query(
        """
    SELECT sp.id
    FROM SchedulePoll sp
    JOIN sp.meeting m
    WHERE sp.status = :pollStatus
      AND sp.deadline <= :now
      AND m.status = :meetingStatus
      AND m.deletedAt IS NULL
    """,
    )
    fun findExpiredIds(
        @Param("pollStatus") pollStatus: SchedulePollStatus,
        @Param("meetingStatus") meetingStatus: MeetingStatus,
        @Param("now") now: LocalDateTime,
    ): List<Long>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
            SELECT sp
            FROM SchedulePoll sp
            WHERE sp.id = :pollId
            """,
    )
    fun findByIdForUpdate(
        @Param("pollId") pollId: Long,
    ): SchedulePoll?

    @Query(
        """
    SELECT sp.meeting.id
    FROM SchedulePoll sp
    WHERE sp.id = :pollId
    """,
    )
    fun findMeetingIdByPollId(
        @Param("pollId") pollId: Long,
    ): Long?
}
