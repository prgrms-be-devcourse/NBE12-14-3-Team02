package com.prgms.backend.domain.meeting.repository

import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface MeetingMemberRepository : JpaRepository<MeetingMember, Long> {
    // MeetingMemberStatus 상관없이 회원 기록 조회
    fun findByMeetingIdAndUserId(
        meetingId: Long,
        userId: Long,
    ): MeetingMember?

    // 모임원이 현재 실제로 참여 중인지 검사(status = JOINED)
    fun existsByMeetingIdAndUserIdAndStatus(
        meetingId: Long,
        userId: Long,
        status: MeetingMemberStatus,
    ): Boolean

    // 탈퇴한 모임원까지 포함한 전체 모임원 조회(마감된 투표 결과처럼 과거 이력을 집계할 때 사용)
    @EntityGraph(attributePaths = ["user"])
    fun findAllByMeetingId(meetingId: Long): List<MeetingMember>

    // 현재 모임원 목록 조회(status = JOINED인 모임원)
    @EntityGraph(attributePaths = ["user"])
    fun findAllByMeetingIdAndStatus(
        meetingId: Long,
        status: MeetingMemberStatus,
    ): List<MeetingMember>

    // 해당 회원이 JOINED 상태로 참여 중인 모임 목록 조회
    fun findAllByUserIdAndStatus(
        userId: Long,
        status: MeetingMemberStatus,
    ): List<MeetingMember>

    // 삭제(soft delete)된 모임인지 검사
    @EntityGraph(attributePaths = ["meeting", "meeting.host"])
    fun findAllByUserIdAndStatusAndMeetingDeletedAtIsNull(
        userId: Long,
        status: MeetingMemberStatus,
    ): List<MeetingMember>

    // 모임원 수
    fun countByMeetingIdAndStatus(
        meetingId: Long,
        status: MeetingMemberStatus,
    ): Long

    @Query(
        """
    SELECT mm.meeting.id, COUNT(mm)
    FROM MeetingMember mm
    WHERE mm.meeting.id IN :meetingIds
      AND mm.status = :status
    GROUP BY mm.meeting.id
    """,
    )
    fun countByMeetingIdsAndStatus(
        @Param("meetingIds") meetingIds: List<Long>,
        @Param("status") status: MeetingMemberStatus,
    ): List<Array<Any>>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
    SELECT mm
    FROM MeetingMember mm
    WHERE mm.meeting.id = :meetingId
      AND mm.user.id = :userId
    """,
    )
    fun findByMeetingIdAndUserIdForUpdate(
        @Param("meetingId") meetingId: Long,
        @Param("userId") userId: Long,
    ): MeetingMember?

    @EntityGraph(attributePaths = ["user"])
    @Query(
        """
    SELECT mm
    FROM MeetingMember mm
    WHERE mm.meeting.id = :meetingId
      AND mm.joinedAt <= :atTime
      AND (
          mm.leftAt IS NULL
          OR mm.leftAt > :atTime
      )
    """,
    )
    fun findParticipantsAt(
        @Param("meetingId") meetingId: Long,
        @Param("atTime") atTime: LocalDateTime,
    ): List<MeetingMember>
}
