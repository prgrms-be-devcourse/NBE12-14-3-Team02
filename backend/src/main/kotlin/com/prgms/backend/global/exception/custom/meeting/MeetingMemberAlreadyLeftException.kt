package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

class MeetingMemberAlreadyLeftException(
    meetingId: Long,
    userId: Long,
) : BusinessException(409, "이미 탈퇴한 모임원입니다. meetingId = $meetingId, userId = $userId")
