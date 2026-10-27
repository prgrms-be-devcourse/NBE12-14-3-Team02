package com.prgms.backend.domain.expense

import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest
import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest.SplitMode
import com.prgms.backend.domain.expense.dto.ExpenseResponse
import com.prgms.backend.domain.expense.dto.ExpenseResponse.Share
import com.prgms.backend.domain.expense.entity.Expense.Companion.register
import com.prgms.backend.domain.expense.entity.ExpenseRegistration
import com.prgms.backend.domain.expense.repository.ExpenseRegistrationRepository
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.expense.service.ExpenseRegistrationService
import com.prgms.backend.domain.expense.service.ExpenseService
import com.prgms.backend.domain.settlement.integration.MeetingAccessPort
import com.prgms.backend.global.exception.BusinessException
import jakarta.validation.Validation
import jakarta.validation.ValidatorFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.security.Principal
import java.util.Optional

internal class ExpenseRegistrationServiceTest {
    private val expenseService: ExpenseService = mock()
    private val access: MeetingAccessPort = mock()
    private val expenses: ExpenseRepository = mock()
    private val registrations: ExpenseRegistrationRepository = mock()
    private val principal = Principal { "user" }
    private lateinit var validation: ValidatorFactory
    private lateinit var service: ExpenseRegistrationService

    @BeforeEach
    fun setup() {
        validation = Validation.buildDefaultValidatorFactory()
        service =
            ExpenseRegistrationService(
                expenses = expenseService,
                access = access,
                expenseRepository = expenses,
                registrations = registrations,
                validator = validation.validator,
            )
        whenever(access.requireMember(10, principal)).thenReturn(
            MeetingAccessPort.Context(
                memberId = 1,
                leader = false,
                meetingOpen = true,
                memberIds = setOf(1L, 2L, 3L),
            ),
        )
        whenever(
            expenseService.create(
                eq(10L),
                eq(principal),
                anyOrNull(),
            ),
        ).thenReturn(response(7))
        whenever(registrations.save(any<ExpenseRegistration>()))
            .thenAnswer { invocation -> invocation.getArgument<ExpenseRegistration>(0) }
    }

    @AfterEach
    fun closeValidator() {
        validation.close()
    }

    @Test
    fun `첫 요청은 지출을 등록하고 소문자 요청 번호·지문·지출 ID를 함께 저장`() {
        val response = service.create(10, principal, equalRequest(), KEY)

        assertEquals(7L, response.id)
        verify(expenseService).create(10, principal, equalRequest())
        val saved = savedRegistration()
        assertEquals(10L, saved.meetingId)
        assertEquals(1L, saved.memberId)
        assertEquals(STORED_KEY, saved.requestKey)
        assertEquals(7L, saved.expenseId)
        assertEquals(EQUAL_FINGERPRINT, saved.fingerprint)
    }

    @Test
    fun `지문은 변환 전 Java 구현과 같고, 지수 표기(1e4)와 일반 표기의 금액은 같은 값이다`() {
        service.create(10, principal, exactRequest(BigDecimal("1E+4"), BigDecimal("6E+3")), KEY)
        assertEquals(EXACT_FINGERPRINT, savedRegistration().fingerprint)

        reset(registrations)
        whenever(registrations.save(any<ExpenseRegistration>()))
            .thenAnswer { invocation -> invocation.getArgument<ExpenseRegistration>(0) }
        service.create(
            10,
            principal,
            exactRequest(BigDecimal.valueOf(10000), BigDecimal.valueOf(6000)),
            KEY,
        )
        assertEquals(EXACT_FINGERPRINT, savedRegistration().fingerprint)
    }

    @Test
    fun `같은 요청 번호로 같은 내용을 다시 보내면 새로 등록하지 않고 기존 지출을 반환`() {
        registered(EQUAL_FINGERPRINT)
        val expense =
            register(
                meetingId = 10,
                payerMemberId = 1,
                title = "식사",
                amount = 30000,
                memo = "메모",
                shares = mapOf(1L to 10000L, 2L to 10000L, 3L to 10000L),
                splitMode = "EQUAL",
                roundingUnit = 100,
                remainderMemberId = null,
            )
        ReflectionTestUtils.setField(expense, "id", 7L)
        whenever(expenses.findById(7L))
            .thenReturn(Optional.of(expense))

        val response = service.create(10, principal, equalRequest(), KEY)

        assertEquals(7L, response.id)
        assertEquals(30000L, response.amount)
        verify(expenseService, never()).create(
            any<Long>(),
            anyOrNull(),
            anyOrNull(),
        )
        verify(registrations, never())
            .save(any<ExpenseRegistration>())
    }

    @Test
    fun `같은 요청 번호로 내용이 다른 요청은 409`() {
        registered(EQUAL_FINGERPRINT)
        assertCode(409) {
            service.create(
                10,
                principal,
                exactRequest(BigDecimal.valueOf(10000), BigDecimal.valueOf(6000)),
                KEY,
            )
        }
        verify(expenseService, never()).create(
            any<Long>(),
            anyOrNull(),
            anyOrNull(),
        )
    }

    @Test
    fun `등록 후 삭제된 지출을 같은 요청 번호로 다시 보내면 복원하지 않고 410`() {
        registered(EQUAL_FINGERPRINT)
        whenever(expenses.findById(7L))
            .thenReturn(Optional.empty())
        assertCode(410) { service.create(10, principal, equalRequest(), KEY) }
        verify(expenseService, never()).create(
            any<Long>(),
            anyOrNull(),
            anyOrNull(),
        )
    }

    @Test
    fun `모임 ID나 요청 번호 형식이 잘못되면 모임 잠금 전에 400`() {
        for (key in arrayOf<String?>(null, "", "not-a-uuid", KEY + "0", KEY.replace('-', '_'))) {
            assertCode(400) { service.create(10, principal, equalRequest(), key) }
        }
        assertCode(400) { service.create(0, principal, equalRequest(), KEY) }
        verifyNoInteractions(access, expenseService, registrations)
    }

    @Test
    fun `요청 본문이 없거나 검증에 실패하면 400이고 등록하지 않는다`() {
        val blankTitle =
            ExpenseCreateRequest(
                title = " ",
                amount = BigDecimal.TEN,
                memo = null,
                splitMode = SplitMode.EQUAL,
                roundingUnit = 1,
                remainderMemberId = null,
                participants =
                    listOf(
                        ExpenseCreateRequest.Participant(
                            1L,
                            null,
                        ),
                    ),
                randomRemainder = false,
            )
        assertCode(400) { service.create(10, principal, null, KEY) }
        assertCode(400) { service.create(10, principal, blankTitle, KEY) }
        verify(expenseService, never()).create(
            any<Long>(),
            anyOrNull(),
            anyOrNull(),
        )
        verify(registrations, never())
            .save(any<ExpenseRegistration>())
    }

    private fun registered(fingerprint: String) {
        whenever(
            registrations.findByMeetingIdAndMemberIdAndRequestKey(
                10L,
                1L,
                STORED_KEY,
            ),
        ).thenReturn(
            ExpenseRegistration(
                meetingId = 10,
                memberId = 1,
                requestKey = STORED_KEY,
                fingerprint = fingerprint,
                expenseId = 7,
            ),
        )
    }

    private fun savedRegistration(): ExpenseRegistration {
        val captor =
            argumentCaptor<ExpenseRegistration>()
        verify(registrations)
            .save(captor.capture())
        return captor.firstValue
    }

    private fun equalRequest(): ExpenseCreateRequest =
        ExpenseCreateRequest(
            title = "식사",
            amount = BigDecimal.valueOf(30000),
            memo = "메모",
            splitMode = SplitMode.EQUAL,
            roundingUnit = 100,
            remainderMemberId = 1L,
            participants =
                listOf(
                    ExpenseCreateRequest.Participant(
                        1L,
                        null,
                    ),
                    ExpenseCreateRequest.Participant(2L, null),
                    ExpenseCreateRequest.Participant(3L, null),
                ),
            randomRemainder = false,
        )

    private fun exactRequest(
        total: BigDecimal,
        secondShare: BigDecimal,
    ): ExpenseCreateRequest =
        ExpenseCreateRequest(
            title = "택시",
            amount = total,
            memo = null,
            splitMode = SplitMode.EXACT,
            roundingUnit = null,
            remainderMemberId = null,
            participants =
                listOf(
                    ExpenseCreateRequest.Participant(
                        1L,
                        BigDecimal.valueOf(4000),
                    ),
                    ExpenseCreateRequest.Participant(2L, secondShare),
                ),
            randomRemainder = false,
        )

    private fun response(id: Long): ExpenseResponse =
        ExpenseResponse(
            id = id,
            meetingId = 10L,
            payerMemberId = 1L,
            title = "식사",
            amount = 30000,
            memo = "메모",
            splitMode = "EQUAL",
            roundingUnit = 100,
            remainderMemberId = 1L,
            participants = listOf(Share(1, 10000)),
            createdAt = null,
            hasReceipt = false,
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

    companion object {
        private const val KEY = "3F2504E0-4F89-11D3-9A0C-0305E82C3301"
        private val STORED_KEY: String = KEY.lowercase()

        // 변환 전 Java 구현(69460b6 이전 ExpenseRegistrationService.fingerprint)으로 계산한 값입니다.
        // DB에 저장된 지문과 비교하므로 구현을 바꿔도 같은 요청은 같은 값이어야 합니다.
        private const val EQUAL_FINGERPRINT =
            "e9bd728eae404ad08381a529f17771efc438d44ddec07227b7e1ed5e6d11285d"
        private const val EXACT_FINGERPRINT =
            "4ee13e84da4c87d958187ce8f3779d5e217e1f021a87114f2b8cab2976df45cd"
    }
}
