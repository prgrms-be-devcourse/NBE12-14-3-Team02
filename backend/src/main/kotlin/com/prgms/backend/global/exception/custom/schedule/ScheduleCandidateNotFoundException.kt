package com.prgms.backend.global.exception.custom.schedule

import com.prgms.backend.global.exception.BusinessException

class ScheduleCandidateNotFoundException(
    candidateId: Long,
) : BusinessException(404, "일정 후보가 존재하지 않습니다. = $candidateId")
