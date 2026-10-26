package com.prgms.backend.global.exception.custom.schedule

import com.prgms.backend.global.exception.BusinessException

class SchedulePollAlreadyExistsException(
    meetingId: Long,
) : BusinessException(409, "이미 일정 투표가 존재합니다. meetingId = $meetingId")
