package com.prgms.backend.global.exception.custom.content

import com.prgms.backend.global.exception.BusinessException

class ContentPollNotFoundException(
    meetingId: Long?,
) : BusinessException(404, "콘텐츠 투표가 존재하지 않습니다. meetingId = $meetingId")
