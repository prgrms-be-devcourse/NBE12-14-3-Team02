package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

class MeetingHostRequiredException : BusinessException(403, "모임장만 수행할 수 있습니다.")
