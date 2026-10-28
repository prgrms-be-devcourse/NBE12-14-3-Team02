package com.prgms.backend.global.exception.custom.content

import com.prgms.backend.global.exception.BusinessException

class ContentPollNotClosedException(
    message: String? = "마감되지 않은 투표는 확정할 수 없습니다.",
) : BusinessException(409, message)
