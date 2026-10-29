package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

class MeetingHostCannotLeaveException : BusinessException(409, "진행 중인 모임의 모임장은 탈퇴할 수 없습니다.")
