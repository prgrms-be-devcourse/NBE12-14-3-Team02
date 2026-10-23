package com.prgms.backend.global.exception.custom.user

import com.prgms.backend.global.exception.BusinessException

class UserNotFoundException(
    message: String,
) : BusinessException(404, message)
