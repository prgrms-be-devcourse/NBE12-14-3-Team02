package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

class MeetingAccessDeniedException(
    meetingId: Long,
    userId: Long,
) : BusinessException(403, "모임에 대한 권한이 없습니다. meetingId = $meetingId, userId = $userId")
