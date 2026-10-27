package com.prgms.backend.domain.settlement

import com.prgms.backend.domain.expense.entity.Expense
import com.prgms.backend.domain.expense.repository.ExpenseRegistrationRepository
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.settlement.dto.SettlementResponse
import com.prgms.backend.domain.settlement.entity.SettlementAccountSnapshot
import com.prgms.backend.domain.settlement.repository.SettlementAccountRepository
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.domain.settlement.service.SettlementCalculator
import com.prgms.backend.domain.settlement.service.SettlementCalculator.Transfer
import com.prgms.backend.support.MySqlContainerSupport
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.jdbc.Sql
import java.time.LocalDateTime
import kotlin.test.assertNotNull

// fixture는 전환 전 Java 커밋 1134c4d가 실제 MySQL에 저장한 행이다.
// 현재 엔티티로 준비 데이터를 저장하지 않아 양쪽 매핑이 함께 틀리는 회귀도 잡는다.
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql("/settlement/java-persisted-fixture.sql")
internal class SettlementJavaPersistenceCompatibilityTest : MySqlContainerSupport() {
    @Autowired
    private lateinit var expenses: ExpenseRepository

    @Autowired
    private lateinit var registrations: ExpenseRegistrationRepository

    @Autowired
    private lateinit var settlements: SettlementRepository

    @Autowired
    private lateinit var accounts: SettlementAccountRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `Java가 저장한 기존·지정·균등 지출을 읽어도 큰 금액·null 옵션·차액과 정산 결과가 같다`() {
        entityManager.clear()
        val found: List<Expense> = expenses.findByMeetingIdOrderByIdAsc(MEETING_ID)
        assertEquals(3, found.size)
        val legacy = found[0]
        assertEquals(900000L, legacy.id)
        assertEquals(41L, legacy.payerMemberId)
        assertEquals("기존 균등 지출", legacy.title)
        assertEquals(10L, legacy.amount)
        assertEquals(CREATED_AT, legacy.createdAt)
        assertNull(legacy.memo)
        assertNull(legacy.splitMode)
        assertNull(legacy.roundingUnit)
        assertNull(legacy.remainderMemberId)
        assertEquals(setOf(5L, 17L, 41L), legacy.getParticipantIds())
        assertTrue(legacy.getParticipantAmounts().isEmpty())

        val exact = found[1]
        assertEquals(900001L, exact.id)
        assertEquals(17L, exact.payerMemberId)
        assertEquals(3000000001L, exact.amount)
        assertEquals("EXACT", exact.splitMode)
        assertEquals("Java에서 저장", exact.memo)
        assertEquals("java/receipt", exact.receiptKey)
        assertEquals(CREATED_AT, exact.createdAt)
        assertNull(exact.roundingUnit)
        assertNull(exact.remainderMemberId)
        assertEquals(setOf(5L, 17L, 41L), exact.getParticipantIds())
        assertEquals(
            mapOf(5L to 0L, 17L to 1L, 41L to 3000000000L),
            exact.getParticipantAmounts(),
        )

        val equal = found[2]
        assertEquals(10000L, equal.amount)
        assertEquals("EQUAL", equal.splitMode)
        assertEquals(100, equal.roundingUnit)
        assertEquals(41L, equal.remainderMemberId)
        assertEquals(
            mapOf(5L to 3300L, 17L to 3300L, 41L to 3400L),
            equal.getParticipantAmounts(),
        )
        val registration =
            assertNotNull(
                registrations.findByMeetingIdAndMemberIdAndRequestKey(
                    MEETING_ID,
                    17,
                    "12345678-1234-1234-1234-123456789abc",
                ),
            )
        assertEquals(exact.id, registration.expenseId)
        assertEquals("a".repeat(64), registration.fingerprint)

        val result =
            SettlementCalculator().calculate(MEETING_ID, found, setOf(5L, 17L, 41L))
        assertEquals(BALANCES, result.balances)
        assertEquals(TRANSFERS, result.transfers)
    }

    @Test
    fun `Java가 확정한 정산의 금액·목록 순서·계좌 스냅샷·상태·시각·버전과 회원별 응답이 유지된다`() {
        entityManager.clear()
        val closed = assertNotNull(settlements.findByMeetingId(MEETING_ID))
        assertEquals(900000L, closed.id)
        assertEquals("CLOSED", closed.status.name)
        assertEquals(41L, closed.closedByMemberId)
        assertEquals(
            LocalDateTime.of(2026, 10, 2, 13, 45, 57, 654321000),
            closed.closedAt,
        )
        assertEquals(0L, closed.version)
        assertEquals(
            BALANCES,
            closed
                .getBalances()
                .map { b ->
                    SettlementCalculator.Balance(
                        b.memberId,
                        b.paidAmount,
                        b.shareAmount,
                    )
                }.toList(),
        )
        assertEquals(
            TRANSFERS,
            closed
                .getTransfers()
                .map { t ->
                    Transfer(
                        t.senderId,
                        t.recipientId,
                        t.amount,
                    )
                }.toList(),
        )
        assertEquals(1, closed.getAccounts().size)
        val snapshot: SettlementAccountSnapshot = closed.getAccounts().first()
        assertEquals(17L, snapshot.memberId)
        assertEquals("은행", snapshot.bankName)
        assertEquals("001-234", snapshot.accountNumber)
        assertEquals("\u00a0예금주", snapshot.accountHolder)
        val response = SettlementResponse.forMember(closed, 41, false)
        assertEquals(BALANCES, response.balances)
        assertEquals(TRANSFERS, response.transfers)
        assertEquals("001-234", response.accounts.first().accountNumber)
        assertTrue(response.missingAccountMemberIds.isEmpty())
        val mine = SettlementResponse.forMember(closed, 41, true)
        assertEquals(
            listOf(BALANCES[2]),
            mine.balances,
        )
        assertEquals(listOf(TRANSFERS[1]), mine.transfers)

        val open = assertNotNull(settlements.findByMeetingId(890002))
        assertEquals("OPEN", open.status.name)
        assertEquals(0L, open.version)
        assertNull(open.closedAt)
        assertNull(open.closedByMemberId)
        assertTrue(open.getBalances().isEmpty())
        assertTrue(open.getTransfers().isEmpty())
        assertTrue(open.getAccounts().isEmpty())

        // 현재 계좌가 바뀌어도 Java가 확정한 스냅샷은 독립적으로 유지되어야 한다.
        assertNotNull(
            accounts.findByMeetingIdAndMemberId(
                MEETING_ID,
                17,
            ),
        ).update("새은행", "999", "새예금주")
        entityManager.flush()
        entityManager.clear()
        val reloaded =
            assertNotNull(settlements.findByMeetingId(MEETING_ID))
        assertEquals("001-234", reloaded.getAccounts().first().accountNumber)
        assertEquals(0L, reloaded.version)
    }

    @Test
    fun `Java가 저장한 기존 지출을 Kotlin으로 수정해 재조회해도 금액·분담액·등록 시각이 유지된다`() {
        val legacy = expenses.findById(900000L).orElseThrow()
        legacy.update("전환 후 수정", 13, "수정 메모", mapOf(5L to 13L), "EXACT", null, null)
        entityManager.flush()
        entityManager.clear()
        val updated = expenses.findById(900000L).orElseThrow()
        assertEquals(13L, updated.amount)
        assertEquals("전환 후 수정", updated.title)
        assertEquals("수정 메모", updated.memo)
        assertEquals("EXACT", updated.splitMode)
        assertEquals(setOf(5L), updated.getParticipantIds())
        assertEquals(mapOf(5L to 13L), updated.getParticipantAmounts())
        assertEquals(41L, updated.payerMemberId)
        assertEquals(CREATED_AT, updated.createdAt)
        assertNull(updated.roundingUnit)
        assertNull(updated.remainderMemberId)
    }

    companion object {
        private const val MEETING_ID: Long = 890001
        private val CREATED_AT: LocalDateTime = LocalDateTime.of(2026, 10, 1, 12, 34, 56, 123456000)
        private val BALANCES: List<SettlementCalculator.Balance> =
            listOf(
                SettlementCalculator.Balance(5, 0, 3304),
                SettlementCalculator.Balance(17, 3000000001L, 3304),
                SettlementCalculator.Balance(41, 10010, 3000003403L),
            )
        private val TRANSFERS: List<Transfer> =
            listOf(
                Transfer(5, 17, 3304),
                Transfer(41, 17, 2999993393L),
            )
    }
}
