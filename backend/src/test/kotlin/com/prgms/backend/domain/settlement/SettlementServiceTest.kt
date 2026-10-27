package com.prgms.backend.domain.settlement

import com.prgms.backend.domain.expense.entity.Expense.Companion.register
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.entity.SettlementAccount
import com.prgms.backend.domain.settlement.enums.SettlementStatus
import com.prgms.backend.domain.settlement.integration.MeetingAccessPort
import com.prgms.backend.domain.settlement.integration.SettlementClosedEvent
import com.prgms.backend.domain.settlement.repository.SettlementAccountRepository
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.domain.settlement.service.SettlementCalculator
import com.prgms.backend.domain.settlement.service.SettlementCalculator.Transfer
import com.prgms.backend.domain.settlement.service.SettlementService
import com.prgms.backend.global.exception.BusinessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.util.ReflectionTestUtils
import java.security.Principal
import kotlin.test.assertNotNull

internal class SettlementServiceTest {
    private val access: MeetingAccessPort = mock()
    private val expenses: ExpenseRepository = mock()
    private val settlements: SettlementRepository = mock()
    private val events: ApplicationEventPublisher = mock()
    private val accounts: SettlementAccountRepository = mock()
    private val members: MeetingMemberRepository = mock()
    private val service =
        SettlementService(
            meetingAccess = access,
            expenseRepository = expenses,
            settlementRepository = settlements,
            calculator = SettlementCalculator(),
            eventPublisher = events,
            accountRepository = accounts,
            memberRepository = members,
        )
    private val principal = Principal { "host" }

    @BeforeEach
    fun setup() {
        whenever(access.requireMember(10, principal))
            .thenReturn(context(true, true))
        whenever(access.requireMemberForRead(10, principal))
            .thenReturn(context(false, true))
        whenever(settlements.findByMeetingId(10)).thenReturn(null)
        whenever(settlements.save(any<Settlement>()))
            .thenAnswer { invocation ->
                val saved = invocation.getArgument<Settlement>(0)
                ReflectionTestUtils.setField(saved, "id", 100L)
                saved
            }
    }

    @Test
    fun `모임장이 확정하면 금액·송금 내역·확정자·시각을 함께 저장`() {
        whenever(accounts.findByMeetingId(10))
            .thenReturn(listOf(account(1)))
        whenever(expenses.findByMeetingIdOrderByIdAsc(10)).thenReturn(
            listOf(
                register(
                    10,
                    1,
                    "식사",
                    300,
                    null,
                    mapOf(1L to 100L, 2L to 200L),
                    "EXACT",
                    null,
                    null,
                ),
            ),
        )
        val result = service.confirm(10, principal)
        assertEquals(SettlementStatus.CLOSED, result.status)
        assertEquals(1L, result.closedByMemberId)
        assertNotNull(result.closedAt)
        assertEquals(listOf(Transfer(2, 1, 200)), result.transfers)
        val saved = argumentCaptor<Settlement>()
        verify(settlements).save(saved.capture())
        assertEquals(2, saved.firstValue.getBalances().size)
        assertEquals(
            200L,
            saved.firstValue
                .getTransfers()
                .first()
                .amount,
        )
        verify(events)
            .publishEvent(SettlementClosedEvent(10, 100, result.closedAt))
        assertEquals(1, saved.firstValue.getAccounts().size)
        assertTrue(result.missingAccountMemberIds.isEmpty())
    }

    @Test
    fun `수취인 계좌가 없으면 결과 저장과 이벤트 발행 없이 확정을 거절한다`() {
        val settlement = Settlement(10)
        whenever(settlements.findByMeetingId(10)).thenReturn(settlement)
        whenever(accounts.findByMeetingId(10))
            .thenReturn(listOf(account(2)))
        whenever(expenses.findByMeetingIdOrderByIdAsc(10)).thenReturn(
            listOf(
                register(
                    10,
                    1,
                    "식사",
                    300,
                    null,
                    mapOf(1L to 100L, 2L to 200L),
                    "EXACT",
                    null,
                    null,
                ),
            ),
        )

        val error = assertThrows<BusinessException> { service.confirm(10, principal) }
        assertEquals(409, error.code)
        assertTrue(assertNotNull(error.message).contains("[1]"))
        assertEquals(SettlementStatus.OPEN, settlement.status)
        assertTrue(settlement.getBalances().isEmpty())
        assertTrue(settlement.getTransfers().isEmpty())
        assertNull(settlement.closedAt)
        verify(settlements, never())
            .save(any<Settlement>())
        verifyNoInteractions(events)

        // 계좌를 보완하면 동일 지출을 확정할 수 있습니다.
        whenever(accounts.findByMeetingId(10))
            .thenReturn(listOf(account(1)))
        assertEquals(SettlementStatus.CLOSED, service.confirm(10, principal).status)
    }

    @Test
    fun `송금할 금액이 없으면 계좌 없이 확정할 수 있다`() {
        whenever(expenses.findByMeetingIdOrderByIdAsc(10)).thenReturn(
            listOf(
                register(
                    10,
                    1,
                    "개인 식사",
                    100,
                    null,
                    mapOf(1L to 100L),
                    "EXACT",
                    null,
                    null,
                ),
            ),
        )
        val result = service.confirm(10, principal)
        assertEquals(SettlementStatus.CLOSED, result.status)
        assertTrue(result.transfers.isEmpty())
    }

    @Test
    fun `미리보기는 확정과 같은 수취인 기준으로 미등록 계좌를 안내한다`() {
        whenever(accounts.findByMeetingId(10))
            .thenReturn(listOf(account(2)))
        whenever(expenses.findByMeetingIdOrderByIdAsc(10)).thenReturn(
            listOf(
                register(
                    10,
                    1,
                    "식사",
                    300,
                    null,
                    mapOf(1L to 100L, 2L to 200L),
                    "EXACT",
                    null,
                    null,
                ),
            ),
        )
        assertEquals(
            mutableListOf(1L),
            service
                .preview(10, principal)
                .missingAccounts
                .map { account -> account.memberId }
                .toList(),
        )
        verify(settlements, never())
            .save(any<Settlement>())
        verifyNoInteractions(events)
    }

    private fun account(memberId: Long): SettlementAccount =
        SettlementAccount(
            meetingId = 10,
            memberId = memberId,
            bankName = "은행",
            accountNumber = "001-234",
            accountHolder = "예금주",
        )

    @Test
    fun `일반 모임원은 확정할 수 없다`() {
        whenever(access.requireMember(10, principal))
            .thenReturn(context(false, true))
        assertEquals(
            403,
            assertThrows<BusinessException> { service.confirm(10, principal) }.code,
        )
        verifyNoInteractions(expenses)
        verify(settlements, never())
            .save(any<Settlement>())
    }

    @Test
    fun `종료된 모임과 이미 확정된 정산을 거절`() {
        whenever(access.requireMember(10, principal))
            .thenReturn(context(true, false))
        assertEquals(
            409,
            assertThrows<BusinessException> { service.confirm(10, principal) }.code,
        )
        whenever(access.requireMember(10, principal))
            .thenReturn(context(true, true))
        val settlement = Settlement(10)
        settlement.close(1)
        whenever(settlements.findByMeetingId(10)).thenReturn(settlement)
        assertEquals(
            409,
            assertThrows<BusinessException> { service.confirm(10, principal) }.code,
        )
        verify(settlements, never())
            .save(any<Settlement>())
    }

    @Test
    fun `잘못된 부담액으로 계산에 실패하면 OPEN 상태와 빈 결과를 유지`() {
        val settlement = Settlement(10)
        whenever(settlements.findByMeetingId(10)).thenReturn(settlement)
        whenever(expenses.findByMeetingIdOrderByIdAsc(10)).thenReturn(
            listOf(
                register(10, 1, "오류", 100, null, mapOf(1L to 99L), "EXACT", null, null),
            ),
        )
        assertThrows<IllegalArgumentException> { service.confirm(10, principal) }
        assertEquals(SettlementStatus.OPEN, settlement.status)
        assertTrue(settlement.getBalances().isEmpty())
        assertNull(settlement.closedAt)
        verify(settlements, never())
            .save(any<Settlement>())
    }

    @Test
    fun `미확정 조회는 404, 확정 결과는 재계산 없이 조회`() {
        assertEquals(
            404,
            assertThrows<BusinessException> {
                service.getResult(
                    10,
                    principal,
                )
            }.code,
        )
        val settlement = Settlement(10)
        whenever(settlements.findByMeetingId(10)).thenReturn(settlement)
        assertEquals(
            404,
            assertThrows<BusinessException> {
                service.getResult(
                    10,
                    principal,
                )
            }.code,
        )
        settlement.close(1)
        assertEquals(SettlementStatus.CLOSED, service.getResult(10, principal).status)
        verifyNoInteractions(expenses)
    }

    private fun context(
        leader: Boolean,
        open: Boolean,
    ): MeetingAccessPort.Context =
        MeetingAccessPort.Context(
            memberId = 1,
            leader = leader,
            meetingOpen = open,
            memberIds = setOf(1L, 2L),
        )
}
