package com.prgms.backend.global.exception.custom.content

import com.prgms.backend.global.exception.BusinessException

class ContentPollAlreadyExistsException(
    meetingId: Long?,
) : BusinessException(409, "이미 콘텐츠 투표가 존재합니다. meetingId = $meetingId")
