package com.prgms.backend.domain.settlement

import com.prgms.backend.domain.expense.entity.Expense
import com.prgms.backend.domain.expense.entity.Expense.Companion.register
import com.prgms.backend.domain.settlement.service.SettlementCalculator
import com.prgms.backend.domain.settlement.service.SettlementCalculator.Transfer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.util.ReflectionTestUtils
import java.util.HashMap
import java.util.Random
import kotlin.math.max

internal class SettlementCalculatorTest {
    private val calculator = SettlementCalculator()
    private val members = setOf(1L, 2L, 3L)

    @Test
    fun `한 명이 전액 결제, 지출 3만원, 실제 송금은 2만원`() {
        val expenses =
            listOf(
                expense(
                    1,
                    30000,
                    mapOf(1L to 10000L, 2L to 10000L, 3L to 10000L),
                ),
            )
        val result = calculator.calculate(10, expenses, members)
        assertEquals(
            listOf(
                Transfer(2, 1, 10000),
                Transfer(3, 1, 10000),
            ),
            result.transfers,
        )
        assertConservation(expenses, result)
    }

    @Test
    fun `여러 결제자의 지정 부담액을 다시 균등 분배하지 않는다`() {
        val expenses =
            listOf(
                expense(1, 30000, mapOf(1L to 20000L, 2L to 10000L)),
                expense(2, 12000, mapOf(1L to 4000L, 2L to 4000L, 3L to 4000L)),
            )
        val result = calculator.calculate(10, expenses, members)
        assertEquals(
            listOf(
                SettlementCalculator.Balance(1, 30000, 24000),
                SettlementCalculator.Balance(2, 12000, 14000),
                SettlementCalculator.Balance(3, 0, 4000),
            ),
            result.balances,
        )
        assertEquals(
            listOf(
                Transfer(2, 1, 2000),
                Transfer(3, 1, 4000),
            ),
            result.transfers,
        )
        assertConservation(expenses, result)
    }

    @Test
    fun `부담자가 아닌 결제자는 대신 낸 금액을 전부 돌려받는다`() {
        val expenses = listOf(expense(1, 101, mapOf(2L to 50L, 3L to 51L)))
        val result = calculator.calculate(10, expenses, members)
        assertEquals(
            listOf(
                Transfer(2, 1, 50),
                Transfer(3, 1, 51),
            ),
            result.transfers,
        )
        assertConservation(expenses, result)
    }

    @Test
    fun `각자 부담액만큼 결제하면 송금이 없다`() {
        val expenses =
            listOf(
                expense(1, 100, mapOf(1L to 50L, 2L to 50L)),
                expense(2, 100, mapOf(1L to 50L, 2L to 50L)),
            )
        val result = calculator.calculate(10, expenses, members)
        assertTrue(result.transfers.isEmpty())
        assertConservation(expenses, result)
    }

    @Test
    fun `지출이 없으면 모든 금액이 0원이다`() {
        val result = calculator.calculate(10, mutableListOf(), members)
        assertEquals(3, result.balances.size)
        assertTrue(result.transfers.isEmpty())
        assertConservation(mutableListOf(), result)
    }

    @Test
    fun `기존 지출의 1원 차액을 ID 순서로 배분한다`() {
        val expenses =
            listOf(
                Expense(
                    meetingId = 10,
                    payerMemberId = 1,
                    title = "기존",
                    amount = 10,
                    memo = null,
                    participantIds = members,
                ),
            )
        val result = calculator.calculate(10, expenses, members)
        assertEquals(
            listOf(
                SettlementCalculator.Balance(1, 10, 4),
                SettlementCalculator.Balance(2, 0, 3),
                SettlementCalculator.Balance(3, 0, 3),
            ),
            result.balances,
        )
        assertConservation(expenses, result)
    }

    @Test
    fun `같은 객체 또는 같은 DB ID의 중복 지출을 거절한다`() {
        val first = expense(1, 100, mapOf(2L to 100L))
        assertThrows<IllegalArgumentException> { calculator.calculate(10, listOf(first, first), members) }
        val second = expense(1, 100, mapOf(2L to 100L))
        ReflectionTestUtils.setField(first, "id", 7L)
        ReflectionTestUtils.setField(second, "id", 7L)
        assertThrows<IllegalArgumentException> { calculator.calculate(10, listOf(first, second), members) }
    }

    @Test
    fun `다른 모임·잘못된 참여자·음수 부담액·합계 불일치를 거절한다`() {
        val invalid =
            listOf(
                register(99, 1, "타 모임", 100, null, mapOf(2L to 100L), "EXACT", null, null),
                expense(1, 100, mapOf(9L to 100L)),
                expense(9, 100, mapOf(1L to 100L)),
                expense(1, 100, mapOf(1L to -1L, 2L to 101L)),
                expense(1, 100, mapOf(1L to 99L)),
            )
        for (expense in invalid) {
            assertThrows<IllegalArgumentException> { calculator.calculate(10, listOf(expense), members) }
        }
    }

    @Test
    fun `고정 시드의 200개 사례에서 전체·개인별 금액이 보존된다`() {
        val random = Random(20260923)
        for (scenario in 0..199) {
            val expenses = mutableListOf<Expense>()
            for (index in 0..9) {
                val shares = mutableMapOf<Long, Long>()
                for (id in members.sorted().toList()) {
                    if (random.nextBoolean()) shares.put(id, random.nextInt(100000).toLong())
                }
                if (shares.values.sum() == 0L) shares.put(1L, 1L)
                val total = shares.values.sum()
                val payer = (1 + random.nextInt(3)).toLong()
                expenses.add(
                    if (random.nextInt(4) == 0) {
                        Expense(
                            meetingId = 10,
                            payerMemberId = payer,
                            title = "기존",
                            amount = total,
                            memo = null,
                            participantIds = shares.keys,
                        )
                    } else {
                        expense(payer, total, shares)
                    },
                )
            }
            assertConservation(expenses, calculator.calculate(10, expenses, members))
        }
    }

    private fun expense(
        payer: Long,
        amount: Long,
        shares: Map<Long, Long>,
    ): Expense = Expense.register(10, payer, "식사", amount, null, shares, "EXACT", null, null)

    // 기대 결제액·부담액은 계산기 결과가 아닌 원본 지출에서 독립적으로 집계한다.
    private fun assertConservation(
        expenses: List<Expense>,
        result: SettlementCalculator.Result,
    ) {
        val expectedPaid: MutableMap<Long, Long> = HashMap()
        val expectedShares: MutableMap<Long, Long> = HashMap()
        for (member in members) {
            expectedPaid.put(member, 0L)
            expectedShares.put(member, 0L)
        }
        for (expense in expenses) {
            expectedPaid.merge(
                expense.payerMemberId,
                expense.amount,
            ) { x, y -> Math.addExact(x, y) }
            if (expense.splitMode == null && expense.getParticipantAmounts().isEmpty()) {
                // 기존 지출은 ID 오름차순으로 1원씩 남은 차액을 부담한다.
                val participants = expense.getParticipantIds().sorted().toList()
                for (index in participants.indices) {
                    val share = (
                        expense.amount / participants.size +
                            (if (index < expense.amount % participants.size) 1 else 0)
                    )
                    expectedShares.merge(
                        participants.get(index),
                        share,
                    ) { x, y -> Math.addExact(x, y) }
                }
            } else {
                expense.getParticipantAmounts().forEach { (member, share) ->
                    expectedShares.merge(
                        member,
                        share,
                    ) { x, y -> Math.addExact(x, y) }
                }
            }
        }
        assertEquals(members.size, result.balances.size, "회원별 잔액의 누락 또는 중복")
        assertEquals(
            members,
            result.balances
                .map(SettlementCalculator.Balance::memberId)
                .toSet(),
            "잔액의 회원 집합",
        )
        val total = expenses.sumOf { it.amount }
        assertEquals(
            total,
            result.balances.sumOf { it.paidAmount },
        )
        assertEquals(
            total,
            result.balances.sumOf { it.shareAmount },
        )
        val sent = result.transfers.sumOf { it.amount }
        assertEquals(
            sent,
            result.balances
                .sumOf { b -> max(0, b.netAmount()) },
        )
        assertEquals(
            sent,
            result.balances
                .sumOf { b -> max(0, -b.netAmount()) },
        )
        for (transfer in result.transfers) {
            assertTrue(transfer.amount > 0)
            assertNotEquals(transfer.senderId, transfer.recipientId)
            assertTrue(members.contains(transfer.senderId) && members.contains(transfer.recipientId))
        }
        for (balance in result.balances) {
            assertEquals(
                expectedPaid[balance.memberId],
                balance.paidAmount,
                "모임원 " + balance.memberId + "의 원본 결제액",
            )
            assertEquals(
                expectedShares[balance.memberId],
                balance.shareAmount,
                "모임원 " + balance.memberId + "의 원본 부담액",
            )
            val outgoing =
                result.transfers
                    .filter { t -> t.senderId == balance.memberId }
                    .sumOf { it.amount }
            val incoming =
                result.transfers
                    .filter { t -> t.recipientId == balance.memberId }
                    .sumOf { it.amount }
            assertEquals(
                balance.shareAmount,
                balance.paidAmount + outgoing - incoming,
                "모임원 " + balance.memberId + "의 최종 부담액",
            )
        }
    }
}
