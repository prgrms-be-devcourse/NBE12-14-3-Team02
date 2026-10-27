package com.prgms.backend.domain.expense

import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest
import com.prgms.backend.domain.expense.entity.Expense
import com.prgms.backend.domain.expense.entity.Expense.Companion.register
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.expense.service.ExpenseService
import com.prgms.backend.domain.expense.service.ExpenseSplitCalculator
import com.prgms.backend.domain.expense.service.ReceiptStorage
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.integration.MeetingAccessPort
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.global.exception.BusinessException
import jakarta.validation.Validation
import jakarta.validation.ValidatorFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.security.Principal
import java.time.LocalDateTime
import java.util.Optional

internal class ExpenseServiceTest {
    private val expenses: ExpenseRepository = mock()
    private val settlements: SettlementRepository = mock()
    private val members: MeetingMemberRepository = mock()
    private val access: MeetingAccessPort = mock()
    private val principal = Principal { "user" }
    private lateinit var validation: ValidatorFactory
    private lateinit var service: ExpenseService

    @BeforeEach
    fun setup() {
        validation = Validation.buildDefaultValidatorFactory()
        service =
            ExpenseService(
                expenseRepository = expenses,
                splitCalculator = ExpenseSplitCalculator(),
                meetingAccess = access,
                settlementRepository = settlements,
                memberRepository = members,
                validator = validation.validator,
                receiptStorage = mock<ReceiptStorage>(),
            )
        val context =
            MeetingAccessPort.Context(
                memberId = 1,
                leader = true,
                meetingOpen = true,
                memberIds = setOf(1L, 2L, 3L),
            )
        whenever(access.requireMember(10, principal))
            .thenReturn(context)
        whenever(access.requireMemberForRead(10, principal))
            .thenReturn(context)
        whenever(settlements.findByMeetingId(10)).thenReturn(null)
        val joinedMembers = listOf(member(1), member(2))
        whenever(
            members.findAllByMeetingIdAndStatus(
                10L,
                MeetingMemberStatus.JOINED,
            ),
        ).thenReturn(joinedMembers)
        whenever(expenses.save(any<Expense>()))
            .thenAnswer { invocation -> invocation.getArgument<Expense>(0) }
    }

    @AfterEach
    fun closeValidator() {
        validation.close()
    }

    @Test
    fun `인증된 모임원이 등록하면 결제자와 분담액을 저장`() {
        val response = service.create(10, principal, request(100, 1, 100))
        assertEquals(1L, response.payerMemberId)
        assertEquals(100L, response.amount)
        assertEquals(100L, response.participants.first().amount)
        verify(expenses)
            .save(any<Expense>())
    }

    @Test
    fun `본인 지출 수정은 ID·결제자·등록 시각·영수증을 유지`() {
        val expense = existing(10, 1)
        val created = LocalDateTime.of(2026, 9, 1, 12, 0)
        ReflectionTestUtils.setField(expense, "createdAt", created)
        expense.attachReceipt("receipt/original")
        val response = service.update(10, 7, principal, request(200, 2, 200))
        assertEquals(7L, response.id)
        assertEquals(1L, response.payerMemberId)
        assertEquals(created, response.createdAt)
        assertEquals(200L, response.amount)
        assertEquals(200L, expense.amount)
        assertEquals(
            response.amount,
            response.participants.sumOf { it.amount },
        )
        assertEquals(mapOf(2L to 200L), expense.getParticipantAmounts())
        assertEquals(setOf(2L), expense.getParticipantIds())
        assertEquals("receipt/original", expense.receiptKey)
        verify(expenses, never())
            .save(any<Expense>()) // JPA 변경 감지 사용
    }

    @Test
    fun `다른 사람 지출은 모임장도 수정·삭제 불가`() {
        existing(10, 2)
        assertCode(403) { service.update(10, 7, principal, request(100, 1, 100)) }
        assertCode(403) { service.delete(10, 7, principal) }
        verify(expenses, never())
            .delete(any<Expense>())
    }

    @Test
    fun `타 모임 지출은 조회·수정·삭제 불가`() {
        existing(99, 1)
        assertCode(404) { service.get(10, 7, principal) }
        assertCode(404) { service.update(10, 7, principal, request(100, 1, 100)) }
        assertCode(404) { service.delete(10, 7, principal) }
    }

    @Test
    fun `정산 확정 후 등록·수정·삭제를 모두 차단`() {
        val settlement = Settlement(10)
        settlement.close(1)
        whenever(settlements.findByMeetingId(10)).thenReturn(settlement)
        assertAllMutationsRejected()
    }

    @Test
    fun `모임 종료 후에도 모든 변경을 차단`() {
        whenever(access.requireMember(10, principal))
            .thenReturn(
                MeetingAccessPort.Context(
                    memberId = 1,
                    leader = true,
                    meetingOpen = false,
                    memberIds = setOf(1L),
                ),
            )
        assertAllMutationsRejected()
    }

    @Test
    fun `분담 합계가 틀리거나 입력 검증에 실패하면 저장하지 않는다`() {
        assertCode(400) { service.create(10, principal, request(100, 1, 99)) }
        assertCode(400) { service.create(10, principal, request(0, 1, 0)) }
        verify(expenses, never())
            .save(any<Expense>())
    }

    @Test
    fun `기존 탈퇴 부담자는 유지 가능하지만 새 탈퇴 부담자 추가는 불가`() {
        val expense = existing(10, 1)
        expense.update("기존", 100, null, mapOf(3L to 100L), "EXACT", null, null)
        service.update(10, 7, principal, request(200, 3, 200))
        assertEquals(mapOf(3L to 200L), expense.getParticipantAmounts())
        assertCode(400) { service.create(10, principal, request(200, 3, 200)) }
        assertCode(400) { service.update(10, 7, principal, request(200, 9, 200)) }
    }

    @Test
    fun `본인 지출 삭제는 해당 지출만 삭제 요청`() {
        val expense = existing(10, 1)
        service.delete(10, 7, principal)
        verify(expenses).delete(expense)
    }

    @Test
    fun `확정된 목록도 읽을 수 있지만 편집 가능 여부는 false`() {
        val settlement = Settlement(10)
        settlement.close(1)
        whenever(settlements.findByMeetingId(10)).thenReturn(settlement)
        val expense = existing(10, 1)
        whenever(expenses.findByMeetingIdOrderByIdAsc(10))
            .thenReturn(listOf(expense))
        val result = service.list(10, principal)
        assertFalse(result.editable)
        assertEquals(1, result.expenses.size)
        verify(access, never())
            .requireMember(any<Long>(), anyOrNull())
    }

    private fun assertAllMutationsRejected() {
        assertCode(409) { service.create(10, principal, request(100, 1, 100)) }
        assertCode(409) { service.update(10, 7, principal, request(100, 1, 100)) }
        assertCode(409) { service.delete(10, 7, principal) }
        verify(expenses, never())
            .save(any<Expense>())
        verify(expenses, never())
            .delete(any<Expense>())
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

    private fun member(id: Long): MeetingMember {
        val member = mock<MeetingMember>()
        whenever(member.id).thenReturn(id)
        return member
    }

    private fun request(
        total: Long,
        memberId: Long,
        share: Long,
    ): ExpenseCreateRequest =
        ExpenseCreateRequest(
            title = "식사",
            amount = BigDecimal.valueOf(total),
            memo = null,
            splitMode = ExpenseCreateRequest.SplitMode.EXACT,
            roundingUnit = null,
            remainderMemberId = null,
            participants =
                listOf(
                    ExpenseCreateRequest.Participant(
                        memberId,
                        BigDecimal.valueOf(share),
                    ),
                ),
            randomRemainder = false,
        )

    private fun assertCode(
        code: Int,
        action: () -> Unit,
    ) {
        assertEquals(
            code,
            assertThrows<BusinessException>(action).code,
        )
    }
}
