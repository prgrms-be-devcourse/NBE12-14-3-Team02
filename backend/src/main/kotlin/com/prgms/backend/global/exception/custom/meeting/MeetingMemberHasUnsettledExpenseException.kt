package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

class MeetingMemberHasUnsettledExpenseException : BusinessException(409, "미정산 지출에 포함된 모임원은 탈퇴할 수 없습니다.")
