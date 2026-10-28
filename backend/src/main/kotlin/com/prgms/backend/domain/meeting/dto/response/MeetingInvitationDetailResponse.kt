package com.prgms.backend.domain.meeting.dto.response

import com.prgms.backend.domain.meeting.entity.MeetingInvitation
import java.time.LocalDateTime

data class MeetingInvitationDetailResponse(
    val inviteCode: String,
    val meetingId: Long,
    val meetingName: String,
    val expiresAt: LocalDateTime,
    val alreadyJoined: Boolean,
    val participantCount: Long,
) {
    companion object {
        fun from(
            invitation: MeetingInvitation,
            alreadyJoined: Boolean,
            participantCount: Long,
        ): MeetingInvitationDetailResponse =
            MeetingInvitationDetailResponse(
                inviteCode = invitation.inviteCode,
                meetingId = invitation.meeting.id,
                meetingName = invitation.meeting.name,
                expiresAt = invitation.expiresAt,
                alreadyJoined = alreadyJoined,
                participantCount = participantCount,
            )
    }
}
