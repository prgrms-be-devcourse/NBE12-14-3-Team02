package com.prgms.backend.domain.meeting.repository

import com.prgms.backend.domain.meeting.entity.MeetingInvitation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MeetingInvitationRepository : JpaRepository<MeetingInvitation, Long> {
    // inviteCode로 초대 찾기
    fun findByInviteCode(inviteCode: String): MeetingInvitation?

    // 존재하는 초대인지
    fun existsByInviteCode(inviteCode: String): Boolean

    // 초대 코드 목록 조회
    fun findAllByMeetingId(meetingId: Long): List<MeetingInvitation>

    @Query(
        """
    SELECT invitation.meeting.id
    FROM MeetingInvitation invitation
    WHERE invitation.inviteCode = :inviteCode
    """,
    )
    fun findMeetingIdByInviteCode(
        @Param("inviteCode") inviteCode: String,
    ): Long?
}
