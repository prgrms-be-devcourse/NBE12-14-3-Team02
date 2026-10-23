package com.prgms.backend.global.exception.custom.settlement

import com.prgms.backend.global.exception.BusinessException

class SettlementRequestException(
    status: Int,
    message: String,
) : BusinessException(status, message)
