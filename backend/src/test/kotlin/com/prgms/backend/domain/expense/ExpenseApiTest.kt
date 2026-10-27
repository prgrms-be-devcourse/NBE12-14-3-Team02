package com.prgms.backend.domain.expense

import com.prgms.backend.domain.expense.controller.ExpenseController
import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest
import com.prgms.backend.domain.expense.dto.ExpenseListResponse
import com.prgms.backend.domain.expense.dto.ExpenseResponse
import com.prgms.backend.domain.expense.dto.ExpenseResponse.Share
import com.prgms.backend.domain.expense.service.ExpenseService
import com.prgms.backend.domain.settlement.controller.SettlementController
import com.prgms.backend.domain.settlement.service.SettlementAccountService
import com.prgms.backend.domain.settlement.service.SettlementService
import com.prgms.backend.global.exception.GlobalExceptionHandler
import com.prgms.backend.global.exception.custom.expense.ExpenseRequestException
import com.prgms.backend.global.exception.custom.settlement.SettlementRequestException
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.security.Principal

// HTTP 매핑·JSON·검증·오류 응답
internal class ExpenseApiTest {
    private val expenses: ExpenseService = mock()
    private val settlements: SettlementService = mock()
    private val accounts: SettlementAccountService = mock()
    private val principal = Principal { "user" }
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        mvc =
            MockMvcBuilders
                .standaloneSetup(
                    ExpenseController(expenses),
                    SettlementController(settlements, accounts),
                ).setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    @Test
    fun `등록 201, 목록·상세·수정 200, 삭제 204와 빈 본문`() {
        val response =
            ExpenseResponse(
                id = 7L,
                meetingId = 10L,
                payerMemberId = 1L,
                title = "식사",
                amount = 100,
                memo = null,
                splitMode = "EXACT",
                roundingUnit = null,
                remainderMemberId = null,
                participants = listOf(Share(1, 100)),
                createdAt = null,
                hasReceipt = false,
            )
        whenever(expenses.create(eq(10L), eq(principal), anyOrNull<ExpenseCreateRequest>()))
            .thenReturn(response)
        whenever(expenses.list(10, principal))
            .thenReturn(
                ExpenseListResponse(
                    currentMemberId = 1,
                    leader = true,
                    editable = true,
                    expenses = listOf(response),
                ),
            )
        whenever(expenses.get(10, 7, principal)).thenReturn(response)
        whenever(expenses.update(eq(10L), eq(7L), eq(principal), anyOrNull<ExpenseCreateRequest>()))
            .thenReturn(response)
        mvc
            .perform(
                post(URL)
                    .principal(principal)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        REQUEST,
                    ),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.amount").value(100))
        mvc
            .perform(get(URL).principal(principal))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.expenses[0].id").value(7))
        mvc
            .perform(get(URL + "/7").principal(principal))
            .andExpect(status().isOk())
        mvc
            .perform(
                put(URL + "/7")
                    .principal(principal)
                    .contentType(
                        MediaType.APPLICATION_JSON,
                    ).content(REQUEST),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.participants[0].amount").value(100))
        mvc
            .perform(delete(URL + "/7").principal(principal))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""))
        verify(expenses).delete(10, 7, principal)
    }

    @Test
    fun `목록 응답의 JSON 필드 이름과 null 값을 기존 계약대로 유지`() {
        val response =
            ExpenseResponse(
                id = 7L,
                meetingId = 10L,
                payerMemberId = 1L,
                title = "식사",
                amount = 100,
                memo = null,
                splitMode = "EXACT",
                roundingUnit = null,
                remainderMemberId = null,
                participants = listOf(Share(1, 100)),
                createdAt = null,
                hasReceipt = true,
            )
        whenever(expenses.list(10, principal))
            .thenReturn(
                ExpenseListResponse(
                    currentMemberId = 1,
                    leader = true,
                    editable = false,
                    expenses = listOf(response),
                ),
            )
        mvc
            .perform(get(URL).principal(principal))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentMemberId").value(1))
            .andExpect(jsonPath("$.data.leader").value(true))
            .andExpect(jsonPath("$.data.editable").value(false))
            .andExpect(jsonPath("$.data.expenses[0].meetingId").value(10))
            .andExpect(jsonPath("$.data.expenses[0].payerMemberId").value(1))
            .andExpect(jsonPath("$.data.expenses[0].title").value("식사"))
            .andExpect(
                jsonPath("$.data.expenses[0].splitMode").value("EXACT"),
            ).andExpect(jsonPath("$.data.expenses[0].memo").isEmpty())
            .andExpect(jsonPath("$.data.expenses[0].roundingUnit").isEmpty())
            .andExpect(
                jsonPath("$.data.expenses[0].remainderMemberId").isEmpty(),
            ).andExpect(jsonPath("$.data.expenses[0].createdAt").isEmpty())
            .andExpect(jsonPath("$.data.expenses[0].hasReceipt").value(true))
            .andExpect(
                jsonPath("$.data.expenses[0].participants[0].memberId")
                    .value(1),
            ).andExpect(
                jsonPath("$.data.expenses[0].participants[0].amount")
                    .value(100),
            )
    }

    @Test
    fun `음수 금액의 요청은 서비스 호출 전에 400으로 거절`() {
        mvc
            .perform(
                post(URL)
                    .principal(principal)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(REQUEST.replace("\"amount\":100", "\"amount\":-1")),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
        verifyNoInteractions(expenses)
    }

    @Test
    fun `요청 JSON의 모든 필드를 DTO에 그대로 바인딩`() {
        mvc
            .perform(
                post(URL)
                    .principal(principal)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"title":"식사","amount":100,"memo":"메모","splitMode":"EQUAL","roundingUnit":10,
                         "remainderMemberId":2,"randomRemainder":true,
                         "participants":[{"memberId":1,"amount":null},{"memberId":2}]}
                        
                        """.trimIndent(),
                    ),
            ).andExpect(status().isCreated())
        verify(expenses).create(
            10L,
            principal,
            ExpenseCreateRequest(
                title = "식사",
                amount = BigDecimal.valueOf(100),
                memo = "메모",
                splitMode = ExpenseCreateRequest.SplitMode.EQUAL,
                roundingUnit = 10,
                remainderMemberId = 2L,
                participants =
                    listOf(
                        ExpenseCreateRequest.Participant(
                            1L,
                            null,
                        ),
                        ExpenseCreateRequest.Participant(2L, null),
                    ),
                randomRemainder = true,
            ),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "\"randomRemainder\":null,"])
    fun `randomRemainder 누락·null은 역직렬화 단계에서 400으로 거절`(randomRemainder: String) {
        mvc
            .perform(
                post(URL)
                    .principal(principal)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        (
                            "{\"title\":\"식사\",\"amount\":100,\"splitMode\":\"EXACT\"," + randomRemainder +
                                "\"participants\":[{\"memberId\":1,\"amount\":100}]}"
                        ),
                    ),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
        verifyNoInteractions(expenses)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            ",\"participants\":null",
            ",\"participants\":[]",
            ",\"participants\":[null]",
            ",\"participants\":[{\"amount\":100}]",
            ",\"participants\":[{\"memberId\":-1,\"amount\":100}]",
            ",\"participants\":[{\"memberId\":1,\"amount\":-1}]",
        ],
    )
    fun `참여자 목록의 누락·null·빈 목록·null 원소·잘못된 원소는 Bean Validation에서 400으로 거절`(participants: String) {
        mvc
            .perform(
                post(URL)
                    .principal(principal)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        (
                            "{\"title\":\"식사\",\"amount\":100,\"splitMode\":\"EXACT\",\"randomRemainder\":false" +
                                participants + "}"
                        ),
                    ),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value(not(startsWith("JSON parse error"))))

        verifyNoInteractions(expenses)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 403, 404, 409])
    fun `업무 예외의 HTTP 상태와 메시지를 그대로 반환`(code: Int) {
        doThrow(ExpenseRequestException(code, "지출 변경 불가"))
            .whenever(expenses)
            .delete(10, 7, principal)
        mvc
            .perform(delete(URL + "/7").principal(principal))
            .andExpect(status().`is`(code))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.message").value("지출 변경 불가"))
    }

    @Test
    fun `정산 확정 권한 오류도 403으로 반환`() {
        whenever(settlements.confirm(10, principal))
            .thenThrow(SettlementRequestException(403, "모임장만 확정 가능"))
        mvc
            .perform(
                post("/api/meetings/10/settlement/confirm").principal(principal),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
    }

    companion object {
        private const val URL = "/api/meetings/10/expenses"
        private val REQUEST =
            """
            {"title":"식사","amount":100,"splitMode":"EXACT","randomRemainder":false,
             "participants":[{"memberId":1,"amount":100}]}
            
            """.trimIndent()
    }
}
