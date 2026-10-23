package com.prgms.backend.global.exception.custom.content

import com.prgms.backend.global.exception.BusinessException

class ContentPollClosedException : BusinessException(409, "투표가 마감되어 변경할 수 없습니다.")
