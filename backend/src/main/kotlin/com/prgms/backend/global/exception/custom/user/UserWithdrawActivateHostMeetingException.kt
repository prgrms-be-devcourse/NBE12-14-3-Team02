package com.prgms.backend.global.exception.custom.user

import com.prgms.backend.global.exception.BusinessException

class UserWithdrawActivateHostMeetingException : BusinessException(409, "진행 중인 모임을 먼저 종료해주세요.")
