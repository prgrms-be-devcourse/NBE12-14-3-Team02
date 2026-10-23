package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

class MeetingPollNotCompletedException(
    meetingId: Long,
) : BusinessException(409, "진행 중인 투표가 있어 모임을 종료할 수 없습니다. meetingId = $meetingId")
