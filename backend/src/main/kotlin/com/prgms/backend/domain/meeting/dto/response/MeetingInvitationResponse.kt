package com.prgms.backend.domain.meeting.dto.response

import com.prgms.backend.domain.meeting.entity.MeetingInvitation
import java.time.LocalDateTime

data class MeetingInvitationResponse(
    val id: Long,
    val meetingId: Long,
    val inviteCode: String,
    val expiresAt: LocalDateTime,
    val createdAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(invitation: MeetingInvitation): MeetingInvitationResponse =
            MeetingInvitationResponse(
                id = invitation.id,
                meetingId = invitation.meeting.id,
                inviteCode = invitation.inviteCode,
                expiresAt = invitation.expiresAt,
                createdAt = invitation.createdAt,
            )
    }
}
