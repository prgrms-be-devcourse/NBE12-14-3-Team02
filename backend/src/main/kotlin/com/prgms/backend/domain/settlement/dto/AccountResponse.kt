package com.prgms.backend.domain.settlement.dto

import com.prgms.backend.domain.settlement.entity.SettlementAccount

// 정산 결과의 계좌는 null을 허용하는 스냅샷 컬럼에서 만들어지므로 계좌 정보는 nullable로 둡니다.
data class AccountResponse(
    val memberId: Long,
    val bankName: String?,
    val accountNumber: String?,
    val accountHolder: String?,
) {
    companion object {
        @JvmStatic // Java의 AccountResponse.from(account) 호출 유지
        fun from(account: SettlementAccount): AccountResponse =
            AccountResponse(
                memberId = account.memberId,
                bankName = account.bankName,
                accountNumber = account.accountNumber,
                accountHolder = account.accountHolder,
            )
    }
}
