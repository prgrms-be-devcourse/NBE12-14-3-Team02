package com.prgms.backend.domain.expense.repository

import com.prgms.backend.domain.expense.entity.Expense
import com.prgms.backend.global.exception.custom.expense.ExpenseRequestException
import org.springframework.data.repository.findByIdOrNull

// 다른 모임의 지출은 존재하지 않는 지출과 같은 404로 응답합니다.
fun ExpenseRepository.getMeetingExpense(
    meetingId: Long,
    expenseId: Long,
): Expense =
    findByIdOrNull(expenseId)
        ?.takeIf { it.meetingId == meetingId }
        ?: throw ExpenseRequestException(404, "해당 모임의 지출을 찾을 수 없습니다.")
