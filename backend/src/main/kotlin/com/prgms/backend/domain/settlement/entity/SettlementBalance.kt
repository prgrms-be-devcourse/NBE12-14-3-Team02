package com.prgms.backend.domain.settlement.entity

import jakarta.persistence.Embeddable

// 인당 지불해야 할 금액,받을 금액 계산
@Embeddable
class SettlementBalance(
    memberId: Long,
    paidAmount: Long,
    shareAmount: Long,
) {
    var memberId: Long = memberId
        protected set

    var paidAmount: Long = paidAmount
        protected set

    var shareAmount: Long = shareAmount
        protected set
}
