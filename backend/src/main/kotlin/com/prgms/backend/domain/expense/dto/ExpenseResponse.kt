package com.prgms.backend.domain.expense.dto

import com.prgms.backend.domain.expense.entity.Expense
import java.time.LocalDateTime

data class ExpenseResponse(
    val id: Long,
    val meetingId: Long,
    val payerMemberId: Long,
    val title: String,
    val amount: Long,
    val memo: String?,
    val splitMode: String?,
    val roundingUnit: Int?,
    val remainderMemberId: Long?,
    val participants: List<Share>,
    // 저장 전 지출을 변환하면 기존처럼 null입니다.
    val createdAt: LocalDateTime?,
    val hasReceipt: Boolean,
) {
    data class Share(
        val memberId: Long,
        val amount: Long,
    )

    companion object {
        fun from(expense: Expense): ExpenseResponse =
            ExpenseResponse(
                id = expense.id,
                meetingId = expense.meetingId,
                payerMemberId = expense.payerMemberId,
                title = expense.title,
                amount = expense.amount,
                memo = expense.memo,
                splitMode = expense.splitMode,
                roundingUnit = expense.roundingUnit,
                remainderMemberId = expense.remainderMemberId,
                participants =
                    expense
                        .getParticipantAmounts()
                        .toSortedMap()
                        .map { (memberId, amount) -> Share(memberId, amount) },
                createdAt = expense.createdAt,
                hasReceipt = expense.receiptKey != null,
            )
    }
}
