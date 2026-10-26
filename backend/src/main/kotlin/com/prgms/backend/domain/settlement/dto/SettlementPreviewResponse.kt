package com.prgms.backend.domain.settlement.dto

import com.prgms.backend.domain.settlement.service.SettlementCalculator.Balance
import com.prgms.backend.domain.settlement.service.SettlementCalculator.Transfer

data class SettlementPreviewResponse(
    val balances: List<Balance>,
    val transfers: List<Transfer>,
    val missingAccounts: List<MissingAccount>,
) {
    data class MissingAccount(
        val memberId: Long,
        val nickname: String,
    )
}
