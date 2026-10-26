package com.prgms.backend.domain.settlement.dto

import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.enums.SettlementStatus
import com.prgms.backend.domain.settlement.service.SettlementCalculator.Balance
import com.prgms.backend.domain.settlement.service.SettlementCalculator.Transfer
import java.time.LocalDateTime

data class SettlementResponse(
    val settlementId: Long,
    val meetingId: Long,
    val status: SettlementStatus,
    val closedByMemberId: Long?,
    val closedAt: LocalDateTime?,
    val balances: List<Balance>,
    val transfers: List<Transfer>,
    val accounts: List<AccountResponse>,
    val missingAccountMemberIds: List<Long>,
) {
    companion object {
        @JvmStatic // Java의 SettlementResponse.forMember(...) 호출 유지
        fun forMember(
            settlement: Settlement,
            memberId: Long,
            personalOnly: Boolean,
        ): SettlementResponse {
            val balances =
                settlement
                    .getBalances()
                    .map { Balance(it.memberId, it.paidAmount, it.shareAmount) }
                    .filter { !personalOnly || it.memberId == memberId }
            val transfers =
                settlement
                    .getTransfers()
                    .map { Transfer(it.senderId, it.recipientId, it.amount) }
                    .filter { !personalOnly || it.senderId == memberId || it.recipientId == memberId }
            val snapshots = settlement.getAccounts()
            return SettlementResponse(
                settlementId = settlement.id,
                meetingId = settlement.meetingId,
                status = settlement.status,
                closedByMemberId = settlement.closedByMemberId,
                closedAt = settlement.closedAt,
                balances = balances,
                transfers = transfers,
                accounts =
                    snapshots
                        .filter { account ->
                            transfers.any { it.senderId == memberId && it.recipientId == account.memberId }
                        }.map {
                            AccountResponse(
                                memberId = it.memberId,
                                bankName = it.bankName,
                                accountNumber = it.accountNumber,
                                accountHolder = it.accountHolder,
                            )
                        },
                missingAccountMemberIds =
                    transfers
                        .map { it.recipientId }
                        .distinct()
                        .sorted()
                        .filter { id -> snapshots.none { it.memberId == id } },
            )
        }
    }
}
