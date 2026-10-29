package com.prgms.backend.domain.meeting.dto.response

import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import java.time.LocalDateTime

data class MeetingMemberResponse(
    val id: Long,
    val meetingId: Long,
    val userId: Long,
    val nickname: String,
    val status: MeetingMemberStatus,
    val joinedAt: LocalDateTime?,
    val leftAt: LocalDateTime?,
) {
    companion object {
        fun from(meetingMember: MeetingMember): MeetingMemberResponse =
            MeetingMemberResponse(
                id = meetingMember.id,
                meetingId = meetingMember.meeting.id,
                userId = meetingMember.user.id,
                nickname = meetingMember.user.nickname,
                status = meetingMember.status,
                joinedAt = meetingMember.joinedAt,
                leftAt = meetingMember.leftAt,
            )
    }
}
