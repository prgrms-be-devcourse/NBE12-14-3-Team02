package com.prgms.backend.domain.schedule.vote.repository

import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import com.prgms.backend.domain.schedule.vote.entity.ScheduleVote
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ScheduleVoteRepository : JpaRepository<ScheduleVote, Long> {
    fun existsByScheduleCandidateId(candidateId: Long): Boolean

    fun findByScheduleCandidateIdAndMeetingMemberId(
        candidateId: Long,
        meetingMemberId: Long,
    ): ScheduleVote?

    fun findAllByScheduleCandidateSchedulePollId(schedulePollId: Long): List<ScheduleVote>

    @Modifying
    @Query(
        """
    DELETE FROM ScheduleVote sv
    WHERE sv.meetingMember.id = :meetingMemberId
      AND sv.scheduleCandidate.id IN (
          SELECT sc.id
          FROM ScheduleCandidate sc
          JOIN sc.schedulePoll sp
          WHERE sp.meeting.id = :meetingId
            AND sp.status = :status
            AND sp.deadline > :now
      )
    """,
    )
    fun deleteOpenVotesByMeetingMember(
        @Param("meetingId") meetingId: Long,
        @Param("meetingMemberId") meetingMemberId: Long,
        @Param("status") status: SchedulePollStatus,
        @Param("now") now: LocalDateTime,
    ): Int
}
