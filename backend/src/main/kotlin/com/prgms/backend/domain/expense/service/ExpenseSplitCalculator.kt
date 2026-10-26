package com.prgms.backend.domain.expense.service

import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest
import com.prgms.backend.global.exception.custom.expense.ExpenseRequestException
import org.springframework.stereotype.Component
import java.util.TreeMap
import java.util.concurrent.ThreadLocalRandom

@Component
class ExpenseSplitCalculator {
    data class Result(
        val shares: Map<Long, Long>,
        val remainderMemberId: Long?,
    )

    fun calculate(request: ExpenseCreateRequest): Map<Long, Long> = calculateResult(request).shares

    fun calculateResult(request: ExpenseCreateRequest): Result {
        var selectedMemberId: Long? = null
        val total = checkNotNull(request.amount) { "검증을 통과한 요청의 지출 금액은 null일 수 없습니다." }.longValueExact()
        if (total <= 0) throw invalid("지출 금액은 1원 이상이어야 합니다.")
        val participants =
            checkNotNull(request.participants) { "검증을 통과한 요청의 참여자 목록은 null일 수 없습니다." }
                .map { checkNotNull(it) { "검증을 통과한 요청의 참여자는 null일 수 없습니다." } }
        val shares = TreeMap<Long, Long>()
        for (participant in participants) {
            if (shares.putIfAbsent(memberIdOf(participant), 0L) != null) throw invalid("참여자가 중복되었습니다.")
        }
        if (shares.isEmpty()) throw invalid("참여자를 한 명 이상 선택해주세요.")
        if (request.splitMode == ExpenseCreateRequest.SplitMode.EXACT) {
            if (request.roundingUnit != null || request.remainderMemberId != null || request.randomRemainder) {
                throw invalid("금액 지정 분담에는 분담 단위와 차액 담당자를 지정할 수 없습니다.")
            }
            var sum = 0L
            for (participant in participants) {
                val amount =
                    participant.amount?.longValueExact()
                        ?: throw invalid("각 참여자의 부담 금액을 입력해주세요.")
                if (amount < 0 || amount > total) throw invalid("개인 부담액은 0원 이상, 지출 금액 이하여야 합니다.")
                sum = Math.addExact(sum, amount)
                shares[memberIdOf(participant)] = amount
            }
            if (sum != total) throw invalid("개인 부담액의 합계가 지출 금액과 같아야 합니다.")
        } else {
            val remainderMemberId = request.remainderMemberId
            if (request.randomRemainder && remainderMemberId != null) {
                throw invalid("차액 담당자 직접 지정과 랜덤 선택 중 하나만 사용해주세요.")
            }
            val unit = request.roundingUnit ?: 1
            if (unit !in ROUNDING_UNITS) throw invalid("분담 단위는 1, 10, 100, 1000원 중 선택해주세요.")
            if (participants.any { it.amount != null }) {
                throw invalid("균등 분담에는 개인 금액을 입력하지 마세요.")
            }
            if (remainderMemberId != null && remainderMemberId !in shares) {
                throw invalid("차액 담당자는 해당 지출 참여자여야 합니다.")
            }
            val each = total / shares.size / unit * unit
            val remainder = total - each * shares.size
            shares.replaceAll { _, _ -> each }
            if (remainder > 0) {
                val selected =
                    if (request.randomRemainder) {
                        val candidates = shares.keys.toList()
                        candidates[ThreadLocalRandom.current().nextInt(candidates.size)]
                    } else {
                        remainderMemberId ?: throw invalid("남은 차액을 부담할 참여자를 선택해주세요.")
                    }
                shares[selected] = each + remainder
                selectedMemberId = selected
            }
        }
        return Result(shares, selectedMemberId)
    }

    private fun memberIdOf(participant: ExpenseCreateRequest.Participant): Long =
        checkNotNull(participant.memberId) { "검증을 통과한 요청의 참여자 ID는 null일 수 없습니다." }

    private fun invalid(message: String) = ExpenseRequestException(400, message)

    companion object {
        private val ROUNDING_UNITS = setOf(1, 10, 100, 1000)
    }
}
