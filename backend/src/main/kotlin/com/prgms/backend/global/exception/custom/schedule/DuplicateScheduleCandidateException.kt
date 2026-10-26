package com.prgms.backend.global.exception.custom.schedule

import com.prgms.backend.global.exception.BusinessException
import java.time.LocalDate

class DuplicateScheduleCandidateException(
    candidateDate: LocalDate,
) : BusinessException(409, "이미 있는 일정 후보입니다. = $candidateDate")
