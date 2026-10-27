package com.prgms.backend.domain.expense

import com.prgms.backend.domain.expense.entity.Expense
import com.prgms.backend.domain.expense.entity.Expense.Companion.register
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.expense.service.ReceiptService
import com.prgms.backend.domain.expense.service.ReceiptStorage
import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.integration.MeetingAccessPort
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.global.exception.BusinessException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.security.Principal
import java.util.Optional

internal class ReceiptServiceTest {
    private val expenses: ExpenseRepository = mock()
    private val access: MeetingAccessPort = mock()
    private val settlements: SettlementRepository = mock()
    private val storage: ReceiptStorage = mock()
    private val principal = Principal { "user" }
    private val service =
        ReceiptService(
            expenses = expenses,
            access = access,
            settlements = settlements,
            storage = storage,
        )

    @BeforeEach
    fun setup() {
        val context =
            MeetingAccessPort.Context(
                memberId = 1,
                leader = false,
                meetingOpen = true,
                memberIds = setOf(1L, 2L),
            )
        whenever(access.requireMember(10, principal))
            .thenReturn(context)
        whenever(access.requireMemberForRead(10, principal))
            .thenReturn(context)
        whenever(settlements.findByMeetingId(10)).thenReturn(null)
    }

    @Test
    fun `본인 지출의 영수증을 교체하면 기존 파일은 커밋 후 삭제를 요청`() {
        val expense = existing(10, 1)
        expense.attachReceipt(OLD_KEY)

        service.replace(10, 7, principal, NEW_KEY)

        assertEquals(NEW_KEY, expense.receiptKey)
        verify(storage).deleteAfterCommit(OLD_KEY)
        verify(storage, never())
            .delete(anyOrNull())
        verify(expenses, never())
            .save(any<Expense>()) // JPA 변경 감지 사용
    }

    @Test
    fun `영수증 삭제는 키를 비우고 기존 파일만 커밋 후 삭제`() {
        val expense = existing(10, 1)
        expense.attachReceipt(OLD_KEY)

        service.replace(10, 7, principal, null)

        assertNull(expense.receiptKey)
        verify(storage).deleteAfterCommit(OLD_KEY)
    }

    @Test
    fun `업로드 전 확인은 읽기 권한으로 검사하고 지출을 바꾸지 않는다`() {
        val expense = existing(10, 1)
        expense.attachReceipt(OLD_KEY)

        service.checkUpload(10, 7, principal)

        assertEquals(OLD_KEY, expense.receiptKey)
        verify(access).requireMemberForRead(10, principal)
        verify(access, never())
            .requireMember(any<Long>(), anyOrNull())
        verifyNoInteractions(storage)
    }

    @Test
    fun `다른 사람 지출은 업로드 확인·교체 모두 403`() {
        val expense = existing(10, 2)
        expense.attachReceipt(OLD_KEY)
        assertAllChangesRejected(403)
        assertEquals(OLD_KEY, expense.receiptKey)
    }

    @Test
    fun `다른 모임의 지출이나 없는 지출은 404`() {
        existing(99, 1)
        assertAllChangesRejected(404)
        assertCode(404) { service.read(10, 7, principal) }

        whenever(expenses.findById(7L))
            .thenReturn(Optional.empty())
        assertAllChangesRejected(404)
        assertCode(404) { service.read(10, 7, principal) }
    }

    @Test
    fun `종료된 모임의 영수증은 변경할 수 없다`() {
        val closed =
            MeetingAccessPort.Context(
                memberId = 1,
                leader = false,
                meetingOpen = false,
                memberIds = setOf(1L),
            )
        whenever(access.requireMember(10, principal))
            .thenReturn(closed)
        whenever(access.requireMemberForRead(10, principal))
            .thenReturn(closed)
        existing(10, 1)
        assertAllChangesRejected(409)
    }

    @Test
    fun `정산 확정 후에는 영수증을 변경할 수 없다`() {
        val settlement = Settlement(10)
        settlement.close(1)
        whenever(settlements.findByMeetingId(10)).thenReturn(settlement)
        existing(10, 1)
        assertAllChangesRejected(409)
    }

    @Test
    fun `영수증 조회는 결제자가 아니어도, 모임 종료·정산 확정 후에도 가능`() {
        val closed =
            MeetingAccessPort.Context(
                memberId = 1,
                leader = false,
                meetingOpen = false,
                memberIds = setOf(1L, 2L),
            )
        whenever(access.requireMemberForRead(10, principal))
            .thenReturn(closed)
        val settlement = Settlement(10)
        settlement.close(1)
        whenever(settlements.findByMeetingId(10)).thenReturn(settlement)
        val expense = existing(10, 2)
        expense.attachReceipt(OLD_KEY)
        val image = byteArrayOf(1, 2, 3)
        whenever(storage.read(OLD_KEY)).thenReturn(image)

        assertArrayEquals(image, service.read(10, 7, principal))
        verify(access, never())
            .requireMember(any<Long>(), anyOrNull())
    }

    private fun assertAllChangesRejected(code: Int) {
        assertCode(code) { service.checkUpload(10, 7, principal) }
        assertCode(code) { service.replace(10, 7, principal, NEW_KEY) }
        assertCode(code) { service.replace(10, 7, principal, null) }
        verify(storage, never())
            .deleteAfterCommit(anyOrNull())
    }

    private fun existing(
        meetingId: Long,
        payerId: Long,
    ): Expense {
        val expense =
            register(
                meetingId = meetingId,
                payerMemberId = payerId,
                title = "기존",
                amount = 100,
                memo = null,
                shares = mapOf(1L to 100L),
                splitMode = "EXACT",
                roundingUnit = null,
                remainderMemberId = null,
            )
        ReflectionTestUtils.setField(expense, "id", 7L)
        whenever(expenses.findById(7L))
            .thenReturn(Optional.of(expense))
        return expense
    }

    private fun assertCode(
        code: Int,
        action: () -> Unit,
    ) {
        assertEquals(
            code,
            assertThrows<BusinessException>(action).code,
        )
    }

    companion object {
        private const val OLD_KEY = "old.png"
        private const val NEW_KEY = "new.png"
    }
}
