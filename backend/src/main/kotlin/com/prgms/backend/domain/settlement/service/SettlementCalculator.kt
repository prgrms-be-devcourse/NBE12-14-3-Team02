package com.prgms.backend.domain.settlement.service

import com.prgms.backend.domain.expense.entity.Expense
import org.springframework.stereotype.Component
import java.util.Collections
import java.util.IdentityHashMap
import java.util.TreeMap

@Component
class SettlementCalculator {
    data class Balance(
        val memberId: Long,
        val paidAmount: Long,
        val shareAmount: Long,
    ) {
        // 정산 금액 최종 계산, 양수면 받을 돈, 음수면 보낼 돈
        fun netAmount(): Long = paidAmount - shareAmount
    }

    data class Transfer(
        val senderId: Long,
        val recipientId: Long,
        val amount: Long,
    )

    data class Result(
        val balances: List<Balance>,
        val transfers: List<Transfer>,
    )

    fun calculate(
        meetingId: Long,
        expenses: List<Expense>,
        memberIds: Set<Long>,
    ): Result {
        require(meetingId > 0 && memberIds.isNotEmpty() && memberIds.none { it <= 0 }) {
            "모임과 참여자 정보가 필요합니다."
        }

        val paidAmounts = memberIds.associateWithTo(TreeMap()) { 0L }
        val shareAmounts = memberIds.associateWithTo(TreeMap()) { 0L }

        val seenExpenses = Collections.newSetFromMap(IdentityHashMap<Expense, Boolean>())
        val seenIds = mutableSetOf<Long>()
        for (expense in expenses) {
            require(seenExpenses.add(expense) && (expense.id == 0L || seenIds.add(expense.id))) {
                "지출이 비어 있거나 중복되었습니다."
            }
            validateExpense(meetingId, expense, memberIds)
            val payerId = expense.payerMemberId
            paidAmounts[payerId] = Math.addExact(paidAmounts.getValue(payerId), expense.amount)

            // 다시 균등 분배하지 않고, 지출 등록 시 결정한 부담액을 그대로 합산합니다.
            getShares(expense).forEach { (memberId, amount) ->
                shareAmounts[memberId] = Math.addExact(shareAmounts.getValue(memberId), amount)
            }
        }

        val balances =
            paidAmounts.map { (memberId, paidAmount) ->
                Balance(memberId, paidAmount, shareAmounts.getValue(memberId))
            }
        return Result(balances, createTransfers(balances))
    }

    private fun validateExpense(
        meetingId: Long,
        expense: Expense,
        memberIds: Set<Long>,
    ) {
        val participantIds = expense.getParticipantIds()
        require(
            expense.meetingId == meetingId &&
                expense.amount > 0 &&
                expense.payerMemberId in memberIds &&
                participantIds.isNotEmpty() &&
                memberIds.containsAll(participantIds),
        ) { "모임 또는 지출 참여자 정보를 확인해주세요." }
    }

    private fun getShares(expense: Expense): Map<Long, Long> {
        val shares = expense.getParticipantAmounts()
        if (shares.isEmpty() && expense.splitMode == null) {
            // 개인별 금액을 저장하기 전에 등록된 기존 지출에만 과거 균등 분배 규칙 적용
            val participants = expense.getParticipantIds().sorted()
            val eachAmount = expense.amount / participants.size
            val remainder = expense.amount % participants.size
            return participants.withIndex().associateTo(TreeMap()) { (index, memberId) ->
                memberId to eachAmount + if (index < remainder) 1 else 0
            }
        }
        require(shares.keys == expense.getParticipantIds()) { "지출 참여자와 부담액 정보가 일치하지 않습니다." }
        var total = 0L
        for (amount in shares.values) {
            require(amount >= 0) { "부담액은 음수일 수 없습니다." }
            total = Math.addExact(total, amount)
        }
        require(total == expense.amount) { "부담액 합계가 지출 금액과 다릅니다." }
        return shares
    }

    // 정산할 사람 정하기
    private fun createTransfers(balances: List<Balance>): List<Transfer> {
        val senders = mutableListOf<RemainingAmount>()
        val recipients = mutableListOf<RemainingAmount>()
        for (balance in balances) {
            val netAmount = balance.netAmount()
            when {
                netAmount < 0 -> senders.add(RemainingAmount(balance.memberId, -netAmount))
                netAmount > 0 -> recipients.add(RemainingAmount(balance.memberId, netAmount))
            }
        }

        // 돈 받을 사람과 보내는 사람의 금액을 비교해서 정산 진행
        val transfers = mutableListOf<Transfer>()
        var senderIndex = 0
        var recipientIndex = 0
        // 둘 중 한사람의 돈이 0원(보내거나 받는돈)이 되면 다음 사람 진행
        // ID 순서로 연결하므로 결과가 일정하지만, 최소 송금 횟수를 보장x
        while (senderIndex < senders.size && recipientIndex < recipients.size) {
            val sender = senders[senderIndex]
            val recipient = recipients[recipientIndex]
            val amount = minOf(sender.amount, recipient.amount)
            transfers.add(Transfer(sender.memberId, recipient.memberId, amount))
            sender.amount -= amount
            recipient.amount -= amount
            if (sender.amount == 0L) {
                senderIndex++
            }
            if (recipient.amount == 0L) {
                recipientIndex++
            }
        }
        return transfers
    }

    // 이름으로 모임원과 남은 금액을 구분
    private class RemainingAmount(
        val memberId: Long,
        var amount: Long,
    )
}
