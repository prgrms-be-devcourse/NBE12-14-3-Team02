package com.prgms.backend.global.exception.custom.user

import com.prgms.backend.global.exception.BusinessException

class WithdrawUserException : BusinessException(401, "탈퇴한 회원입니다.")
