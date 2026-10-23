package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

class MeetingNotActiveException(
    meetingId: Long,
) : BusinessException(409, "진행 중인 모임이 아닙니다. meetingId=$meetingId")
