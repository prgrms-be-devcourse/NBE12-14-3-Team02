package com.prgms.backend.domain.settlement.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
class SettlementAccountSnapshot(
    account: SettlementAccount,
) {
    var memberId: Long = account.memberId
        protected set

    // 기존 매핑처럼 null을 허용하는 컬럼입니다.
    @Column(length = 100)
    var bankName: String? = account.bankName
        protected set

    @Column(length = 50)
    var accountNumber: String? = account.accountNumber
        protected set

    @Column(length = 100)
    var accountHolder: String? = account.accountHolder
        protected set
}
