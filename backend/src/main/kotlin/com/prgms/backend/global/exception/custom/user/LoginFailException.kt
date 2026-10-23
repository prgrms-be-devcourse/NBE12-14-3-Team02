package com.prgms.backend.global.exception.custom.user

import com.prgms.backend.global.exception.BusinessException

class LoginFailException : BusinessException(401, "이메일 또는 비밀번호가 일치하지 않습니다.")
