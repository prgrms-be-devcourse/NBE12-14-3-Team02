package com.prgms.backend.domain.settlement.entity

import jakarta.persistence.Embeddable

// 정산 결과 안내
@Embeddable
class SettlementTransfer(
    senderId: Long,
    recipientId: Long,
    amount: Long,
) {
    // 보내는사람
    var senderId: Long = senderId
        protected set

    // 받는사람
    var recipientId: Long = recipientId
        protected set

    // 금액
    var amount: Long = amount
        protected set
}
