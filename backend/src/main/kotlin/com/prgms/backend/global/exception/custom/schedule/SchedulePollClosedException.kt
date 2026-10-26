package com.prgms.backend.global.exception.custom.schedule

import com.prgms.backend.global.exception.BusinessException

class SchedulePollClosedException : BusinessException(409, "이미 마감된 일정 투표입니다.")
