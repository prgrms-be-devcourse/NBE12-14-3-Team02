package com.prgms.backend.global.exception.custom.expense

import com.prgms.backend.global.exception.BusinessException

class ExpenseRequestException(
    status: Int,
    message: String,
) : BusinessException(status, message)
