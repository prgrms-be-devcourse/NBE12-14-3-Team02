package com.prgms.backend.domain.expense.dto

// 버튼 표시의 기준도 서버에서 전달합니다. 변경 요청의 권한은 서버에서 다시 검사합니다.
data class ExpenseListResponse(
    val currentMemberId: Long,
    val leader: Boolean,
    val editable: Boolean,
    val expenses: List<ExpenseResponse>,
)
