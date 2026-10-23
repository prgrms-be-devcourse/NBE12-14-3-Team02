package com.prgms.backend.global.exception.custom.user

import com.prgms.backend.global.exception.BusinessException

class ReissueFailException : BusinessException(401, "reissue에 실패했습니다.")
