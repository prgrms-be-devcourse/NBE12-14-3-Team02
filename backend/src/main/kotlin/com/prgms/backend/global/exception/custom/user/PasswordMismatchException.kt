package com.prgms.backend.global.exception.custom.user

import com.prgms.backend.global.exception.BusinessException

class PasswordMismatchException : BusinessException(400, "비밀번호가 일치하지 않습니다.")
