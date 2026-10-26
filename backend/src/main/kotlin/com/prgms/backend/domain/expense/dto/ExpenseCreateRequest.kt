package com.prgms.backend.domain.expense.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal

// 변환 PR에서는 기존 API 계약 유지를 위해 참조 타입을 nullable로 둡니다.
data class ExpenseCreateRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val title: String?,
    @field:NotNull
    @field:DecimalMin("1")
    @field:Digits(integer = 12, fraction = 0)
    val amount: BigDecimal?,
    @field:Size(max = 2000)
    val memo: String?,
    @field:NotNull
    val splitMode: SplitMode?,
    val roundingUnit: Int?,
    @field:Positive
    val remainderMemberId: Long?,
    @field:NotEmpty
    @field:Size(max = 100)
    @field:Valid
    val participants: List<@NotNull Participant?>?,
    // 기존 boolean처럼 누락·null 요청을 400으로 거절하도록 기본값을 두지 않습니다.
    val randomRemainder: Boolean,
) {
    enum class SplitMode { EQUAL, EXACT }

    data class Participant(
        @field:NotNull
        @field:Positive
        val memberId: Long?,
        @field:DecimalMin("0")
        @field:Digits(integer = 12, fraction = 0)
        val amount: BigDecimal?,
    )
}
