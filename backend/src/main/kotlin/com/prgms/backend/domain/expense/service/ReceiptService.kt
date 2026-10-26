package com.prgms.backend.domain.expense.service

import com.prgms.backend.domain.expense.entity.Expense
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.expense.repository.getMeetingExpense
import com.prgms.backend.domain.settlement.integration.MeetingAccessPort
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.global.exception.custom.expense.ExpenseRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.Principal

@Service
class ReceiptService(
    private val expenses: ExpenseRepository,
    private val access: MeetingAccessPort,
    private val settlements: SettlementRepository,
    private val storage: ReceiptStorage,
) {
    @Transactional(readOnly = true)
    fun checkUpload(
        meetingId: Long,
        expenseId: Long,
        principal: Principal?,
    ) {
        val context: MeetingAccessPort.Context = access.requireMemberForRead(meetingId, principal)
        checkEditable(meetingId, expenseId, context)
    }

    @Transactional
    fun replace(
        meetingId: Long,
        expenseId: Long,
        principal: Principal?,
        key: String?,
    ) {
        val context: MeetingAccessPort.Context = access.requireMember(meetingId, principal)
        val expense = checkEditable(meetingId, expenseId, context)
        val old = expense.receiptKey
        expense.attachReceipt(key)
        storage.deleteAfterCommit(old)
    }

    @Transactional(readOnly = true)
    fun read(
        meetingId: Long,
        expenseId: Long,
        principal: Principal?,
    ): ByteArray {
        access.requireMemberForRead(meetingId, principal)
        return storage.read(expenses.getMeetingExpense(meetingId, expenseId).receiptKey)
    }

    private fun checkEditable(
        meetingId: Long,
        expenseId: Long,
        context: MeetingAccessPort.Context,
    ): Expense {
        if (!context.meetingOpen) throw ExpenseRequestException(409, "종료된 모임의 영수증은 변경할 수 없습니다.")
        settlements.findByMeetingId(meetingId)?.checkOpen()
        val expense = expenses.getMeetingExpense(meetingId, expenseId)
        if (expense.payerMemberId != context.memberId) {
            throw ExpenseRequestException(403, "본인 지출의 영수증만 변경할 수 있습니다.")
        }
        return expense
    }
}
