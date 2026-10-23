package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

class MeetingSettlementNotCompletedException(
    meetingId: Long,
) : BusinessException(409, "최종 정산이 완료되지 않은 모임입니다. meetingId = $meetingId")
