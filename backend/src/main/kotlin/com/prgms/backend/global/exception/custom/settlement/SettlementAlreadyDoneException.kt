package com.prgms.backend.global.exception.custom.settlement

import com.prgms.backend.global.exception.BusinessException

class SettlementAlreadyDoneException : BusinessException(409, "이미 정산이 완료되었습니다.")
