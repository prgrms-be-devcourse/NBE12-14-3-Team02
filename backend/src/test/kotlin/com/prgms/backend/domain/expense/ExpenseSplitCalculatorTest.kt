package com.prgms.backend.domain.expense

import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest
import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest.SplitMode
import com.prgms.backend.domain.expense.service.ExpenseSplitCalculator
import com.prgms.backend.global.exception.custom.expense.ExpenseRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

// 정산 금액 테스트
internal class ExpenseSplitCalculatorTest {
    private val calculator = ExpenseSplitCalculator()
    private val participants: List<ExpenseCreateRequest.Participant> =
        listOf(
            ExpenseCreateRequest.Participant(1L, null),
            ExpenseCreateRequest.Participant(2L, null),
            ExpenseCreateRequest.Participant(3L, null),
        )

    @Test
    fun `3만원을 3명에게 균등 분담`() {
        assertEquals(
            mapOf(1L to 10000L, 2L to 10000L, 3L to 10000L),
            calculator.calculate(equal(30000, 1, null, false)),
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 10, 100, 1000])
    fun `분담 단위와 관계없이 차액 담당자에게 남은 금액을 배분`(unit: Int) {
        val result = calculator.calculateResult(equal(10000, unit, 3L, false))
        val each = (10000 / 3 / unit * unit).toLong()
        assertEquals(
            mapOf(1L to each, 2L to each, 3L to 10000 - each * 2),
            result.shares,
        )
        assertEquals(
            10000L,
            result.shares.values.sum(),
        )
        assertEquals(3L, result.remainderMemberId)
    }

    @Test
    fun `랜덤 결과의 사람은 고정하지 않고 유효한 담당자와 금액 보존을 확인`() {
        repeat(50) {
            val result = calculator.calculateResult(equal(10000, 1, null, true))
            assertTrue(result.shares.containsKey(result.remainderMemberId))
            assertEquals(3334L, result.shares[result.remainderMemberId])
            assertEquals(
                10000L,
                result.shares.values.sum(),
            )
        }
    }

    @Test
    fun `직접 지정 금액과 0원 부담자를 그대로 유지`() {
        val request =
            exact(
                listOf(
                    ExpenseCreateRequest.Participant(
                        1L,
                        BigDecimal.ZERO,
                    ),
                    ExpenseCreateRequest.Participant(2L, BigDecimal.valueOf(10000)),
                ),
            )
        assertEquals(
            mapOf(1L to 0L, 2L to 10000L),
            calculator.calculate(request),
        )
    }

    @Test
    fun `금액 합계 불일치·누락·음수 입력을 거절`() {
        for (amount in listOf(BigDecimal.valueOf(9999), BigDecimal.valueOf(-1))) {
            assertThrows<ExpenseRequestException> {
                calculator.calculate(
                    exact(
                        listOf(
                            ExpenseCreateRequest.Participant(
                                1L,
                                amount,
                            ),
                        ),
                    ),
                )
            }
        }
        assertThrows<ExpenseRequestException> {
            calculator.calculate(
                exact(
                    listOf(
                        ExpenseCreateRequest.Participant(
                            1L,
                            null,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `차액 담당자 누락·외부인·옵션 충돌·잘못된 단위를 거절`() {
        for (request in listOf(
            equal(10000, 1, null, false),
            equal(10000, 1, 9L, false),
            equal(10000, 1, 1L, true),
            equal(10000, 7, 1L, false),
        )) {
            assertEquals(
                400,
                assertThrows<ExpenseRequestException> { calculator.calculate(request) }.code,
            )
        }
    }

    @Test
    fun `참여자 중복과 빈 목록을 거절`() {
        for (list in listOf(
            emptyList<ExpenseCreateRequest.Participant>(),
            listOf(
                ExpenseCreateRequest.Participant(1L, null),
                ExpenseCreateRequest.Participant(1L, null),
            ),
        )) {
            val request =
                ExpenseCreateRequest(
                    title = "식사",
                    amount = BigDecimal.TEN,
                    memo = null,
                    splitMode = SplitMode.EQUAL,
                    roundingUnit = 1,
                    remainderMemberId = null,
                    participants = list,
                    randomRemainder = false,
                )
            assertThrows<ExpenseRequestException> { calculator.calculate(request) }
        }
    }

    @Test
    fun `총금액의 소수 부분을 버리지 않고 거절한다`() {
        val request =
            ExpenseCreateRequest(
                title = "식사",
                amount = BigDecimal("10000.5"),
                memo = null,
                splitMode = SplitMode.EXACT,
                roundingUnit = null,
                remainderMemberId = null,
                participants =
                    listOf(
                        ExpenseCreateRequest.Participant(
                            1L,
                            BigDecimal.valueOf(10000),
                        ),
                    ),
                randomRemainder = false,
            )

        assertThrows<ArithmeticException> { calculator.calculate(request) }
    }

    @Test
    fun `개인 부담액의 소수 부분을 버리지 않고 거절한다`() {
        // 소수를 잘라버리면 합계가 10,000원이 되어 정상 처리될 수 있는 입력이다.
        val request =
            exact(
                listOf(
                    ExpenseCreateRequest.Participant(1L, BigDecimal("5000.5")),
                    ExpenseCreateRequest.Participant(2L, BigDecimal("5000.5")),
                ),
            )

        assertThrows<ArithmeticException> { calculator.calculate(request) }
    }

    private fun equal(
        amount: Long,
        unit: Int,
        remainder: Long?,
        random: Boolean,
    ): ExpenseCreateRequest =
        ExpenseCreateRequest(
            title = "식사",
            amount = BigDecimal.valueOf(amount),
            memo = null,
            splitMode = SplitMode.EQUAL,
            roundingUnit = unit,
            remainderMemberId = remainder,
            participants = participants,
            randomRemainder = random,
        )

    private fun exact(participants: List<ExpenseCreateRequest.Participant>): ExpenseCreateRequest =
        ExpenseCreateRequest(
            title = "식사",
            amount = BigDecimal.valueOf(10000),
            memo = null,
            splitMode = SplitMode.EXACT,
            roundingUnit = null,
            remainderMemberId = null,
            participants = participants,
            randomRemainder = false,
        )
}
