package com.prgms.backend.global.exception.custom.content

import com.prgms.backend.global.exception.BusinessException

class ContentPollAlreadyConfirmedException : BusinessException(409, "이미 확정된 콘텐츠가 있습니다.")
