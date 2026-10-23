package com.prgms.backend.domain.meeting.dto.response

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.enums.MeetingStatus

data class MeetingResponse(
    val id: Long,
    val hostId: Long,
    val name: String,
    val description: String?,
    val status: MeetingStatus,
    val participantCount: Long,
) {
    companion object {
        @JvmStatic
        fun from(
            meeting: Meeting,
            participantCount: Long,
        ): MeetingResponse =
            MeetingResponse(
                id = meeting.id,
                hostId = meeting.host.id,
                name = meeting.name,
                description = meeting.description,
                status = meeting.status,
                participantCount = participantCount,
            )
    }
}
