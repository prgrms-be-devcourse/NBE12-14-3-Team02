package com.prgms.backend.global.exception.custom.schedule

import com.prgms.backend.global.exception.BusinessException

class SchedulePollNotFoundException(
    meetingId: Long,
) : BusinessException(404, "일정 투표를 찾을 수 없습니다. meetingId = $meetingId")
