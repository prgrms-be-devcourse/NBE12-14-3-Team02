package com.prgms.backend.domain.expense

import com.prgms.backend.domain.expense.entity.Expense.Companion.register
import com.prgms.backend.domain.expense.entity.ExpenseRegistration
import com.prgms.backend.domain.expense.repository.ExpenseRegistrationRepository
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.support.MySqlContainerSupport
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import java.util.UUID
import kotlin.test.assertNotNull

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
internal class ExpensePersistenceTest : MySqlContainerSupport() {
    @Autowired
    private lateinit var expenses: ExpenseRepository

    @Autowired
    private lateinit var registrations: ExpenseRegistrationRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `ID가 0인 지출은 신규 저장되고, 재조회 시 컬렉션·등록 시각·분담 정보가 유지`() {
        val expense =
            register(
                meetingId = MEETING_ID,
                payerMemberId = 1,
                title = "  식사\t",
                amount = 300,
                memo = "메모",
                shares = mapOf(1L to 100L, 2L to 200L),
                splitMode = "EXACT",
                roundingUnit = 100,
                remainderMemberId = 2L,
            )
        assertEquals(0L, expense.id)
        // 기존 String.strip() 기준: 일반 공백·탭은 제거하고 NBSP는 유지
        assertEquals("\u00a0 식사\t".trim { Character.isWhitespace(it) }, expense.title)
        assertEquals("\u00a0 식사", expense.title)

        val id = expenses.saveAndFlush(expense).id
        assertNotEquals(0L, id)
        entityManager.clear()

        val found = expenses.findById(id).orElseThrow()
        assertNotSame(expense, found)
        assertEquals(MEETING_ID, found.meetingId)
        assertEquals(1L, found.payerMemberId)
        assertEquals(300L, found.amount)
        assertEquals("메모", found.memo)
        assertNotNull(found.createdAt)
        assertEquals(setOf(1L, 2L), found.getParticipantIds())
        assertEquals(
            mapOf(1L to 100L, 2L to 200L),
            found.getParticipantAmounts(),
        )
        assertEquals("EXACT", found.splitMode)
        assertEquals(100, found.roundingUnit)
        assertEquals(2L, found.remainderMemberId)
        assertNull(found.receiptKey)

        // no-arg 생성자로 복원된 엔티티에서도 도메인 메서드가 컬렉션을 교체
        found.update(" 수정\n", 500, null, mapOf(3L to 500L), "EXACT", null, null)
        found.attachReceipt("receipt/key")
        entityManager.flush()
        entityManager.clear()

        val updated = expenses.findById(id).orElseThrow()
        assertEquals(id, updated.id)
        assertEquals(1L, updated.payerMemberId)
        assertEquals("수정", updated.title)
        assertEquals(500L, updated.amount)
        assertEquals(
            updated.amount,
            updated.getParticipantAmounts().values.sum(),
        )
        assertNull(updated.memo)
        assertEquals("EXACT", updated.splitMode)
        assertNull(updated.roundingUnit)
        assertNull(updated.remainderMemberId)
        assertEquals(setOf(3L), updated.getParticipantIds())
        assertEquals(mapOf(3L to 500L), updated.getParticipantAmounts())
        assertEquals("receipt/key", updated.receiptKey)
        assertEquals(found.createdAt, updated.createdAt)
        assertTrue(expenses.existsMemberInExpense(MEETING_ID, 3L))
        assertFalse(expenses.existsMemberInExpense(MEETING_ID, 2L))
    }

    @Test
    fun `요청 번호는 저장 후 재조회해도 같은 지출을 가리킨다`() {
        val key = UUID.randomUUID().toString()
        val id =
            registrations
                .saveAndFlush(
                    ExpenseRegistration(
                        meetingId = MEETING_ID,
                        memberId = 1,
                        requestKey = key,
                        fingerprint = "f".repeat(64),
                        expenseId = 7,
                    ),
                ).id
        assertNotEquals(0L, id)
        entityManager.clear()

        val found =
            assertNotNull(
                registrations.findByMeetingIdAndMemberIdAndRequestKey(MEETING_ID, 1, key),
            )
        assertEquals(id, found.id)
        assertEquals(7L, found.expenseId)
        assertEquals("f".repeat(64), found.fingerprint)
    }

    companion object {
        private const val MEETING_ID: Long = 780002
    }
}
