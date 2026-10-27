package com.prgms.backend.domain.settlement

import com.jayway.jsonpath.JsonPath
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.entity.SettlementAccountSnapshot
import com.prgms.backend.domain.settlement.entity.SettlementBalance
import com.prgms.backend.domain.settlement.entity.SettlementTransfer
import com.prgms.backend.domain.settlement.repository.SettlementAccountRepository
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.security.JwtTokenProvider
import com.prgms.backend.support.MySqlContainerSupport
import org.hamcrest.Matchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertNotNull

// JWT 인증부터 Controller, Service, Repository, MySQL(Flyway 스키마)까지 mock 없이 함께 검증한다.
// 테스트 트랜잭션으로 감싸지 않아 실제 커밋과 커밋 후 알림 저장까지 확인하고, DB 값은 JdbcTemplate으로 직접 조회한다.
// 데이터가 롤백되지 않으므로 매 테스트마다 새 회원·모임을 만든다.
@SpringBootTest
@AutoConfigureMockMvc
internal class SettlementApiIntegrationTest : MySqlContainerSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var tokens: JwtTokenProvider

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var meetings: MeetingRepository

    @Autowired
    private lateinit var members: MeetingMemberRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transaction: TransactionTemplate

    @Autowired
    private lateinit var settlements: SettlementRepository

    @Autowired
    private lateinit var accounts: SettlementAccountRepository

    private lateinit var host: User
    private lateinit var second: User
    private lateinit var third: User
    private lateinit var outsider: User
    private var meetingId: Long = 0
    private var hostMemberId: Long = 0
    private var secondMemberId: Long = 0
    private var thirdMemberId: Long = 0
    private lateinit var url: String

    @BeforeEach
    fun setUp() {
        // 준비 데이터만 저장소로 만들고, 검증할 동작은 모두 API로 호출한다.
        val suffix = UUID.randomUUID().toString().substring(0, 8)
        host = users.save(User("host-" + suffix + "@test.com", "모임장-" + suffix, "password"))
        second =
            users.save(User("second-" + suffix + "@test.com", "회원2-" + suffix, "password"))
        third =
            users.save(User("third-" + suffix + "@test.com", "회원3-" + suffix, "password"))
        outsider =
            users.save(
                User(
                    "outsider-" + suffix + "@test.com",
                    "외부인-" + suffix,
                    "password",
                ),
            )
        val meeting = meetings.save(Meeting(host, "정산 통합 테스트", null))
        meetingId = meeting.id
        // 저장 순서대로 모임원 ID가 커지므로 송금 순서(보내는 사람 ID 오름차순)를 예상할 수 있다.
        hostMemberId = members.save(MeetingMember(meeting, host)).id
        secondMemberId = members.save(MeetingMember(meeting, second)).id
        thirdMemberId = members.save(MeetingMember(meeting, third)).id
        url = "/api/meetings/" + meetingId + "/settlement"
    }

    @Test
    fun `지출 등록 → 미리보기 → 계좌 등록 → 확정 → 조회까지 API 응답과 DB 저장 값이 일치한다`() {
        // given: 모임장이 30,000원, 두 번째 회원이 6,000원을 결제하고 셋이 같은 금액을 부담
        // 결제액 [모임장 30,000 / 회원2 6,000 / 회원3 0], 부담액 각 12,000
        // → 회원2가 6,000원, 회원3이 12,000원을 모임장에게 보낸다.
        createExpense(
            host,
            "숙소",
            30000,
            mapOf(hostMemberId to 10000L, secondMemberId to 10000L, thirdMemberId to 10000L),
        )
        createExpense(
            second,
            "식사",
            6000,
            mapOf(hostMemberId to 2000L, secondMemberId to 2000L, thirdMemberId to 2000L),
        )

        // 미리보기: 계산 결과와 계좌 미등록 수취인을 보여주지만 DB에는 저장하지 않는다.
        mvc
            .perform(
                get(url + "/preview")
                    .header(HttpHeaders.AUTHORIZATION, bearer(second)),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balances.length()").value(3))
            .andExpect(
                jsonPath("$.data.balances[0].memberId").value(hostMemberId),
            ).andExpect(jsonPath("$.data.balances[0].paidAmount").value(30000))
            .andExpect(
                jsonPath("$.data.balances[0].shareAmount").value(12000),
            ).andExpect(jsonPath("$.data.transfers.length()").value(2))
            .andExpect(
                jsonPath("$.data.transfers[0].senderId").value(secondMemberId),
            ).andExpect(
                jsonPath("$.data.transfers[0].recipientId")
                    .value(hostMemberId),
            ).andExpect(jsonPath("$.data.transfers[0].amount").value(6000))
            .andExpect(
                jsonPath("$.data.transfers[1].senderId").value(thirdMemberId),
            ).andExpect(jsonPath("$.data.transfers[1].amount").value(12000))
            .andExpect(
                jsonPath("$.data.missingAccounts[0].memberId")
                    .value(hostMemberId),
            ).andExpect(
                jsonPath("$.data.missingAccounts[0].nickname")
                    .value(host.nickname),
            )
        assertEquals(
            0,
            count("SELECT COUNT(*) FROM settlements WHERE meeting_id = ?", meetingId),
        )

        // 수취인(모임장) 계좌가 없으면 확정을 거절하고, 정산 행도 남기지 않는다.
        assertError(
            mvc.perform(
                post(url + "/confirm")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            409,
        )
        assertEquals(
            0,
            count("SELECT COUNT(*) FROM settlements WHERE meeting_id = ?", meetingId),
        )

        // 계좌 등록: 은행명·예금주의 앞뒤 공백을 제거해 저장한다.
        mvc
            .perform(
                put(url + "/account")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(
                        MediaType.APPLICATION_JSON,
                    ).content(
                        """
                        {"bankName":" 국민은행 ","accountNumber":"123-456","accountHolder":"\t모임장 "}
                        
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bankName").value("국민은행"))
            .andExpect(jsonPath("$.data.accountHolder").value("모임장"))
        assertEquals(
            mapOf("bank_name" to "국민은행", "account_number" to "123-456", "account_holder" to "모임장"),
            jdbc.queryForMap(
                "SELECT bank_name, account_number, account_holder FROM settlement_accounts" +
                    " WHERE meeting_id = ? AND member_id = ?",
                meetingId,
                hostMemberId,
            ),
        )

        // 모임장이 아니면 확정할 수 없다.
        assertError(
            mvc.perform(
                post(url + "/confirm")
                    .header(HttpHeaders.AUTHORIZATION, bearer(second)),
            ),
            403,
        )

        // when: 모임장이 확정
        mvc
            .perform(
                post(url + "/confirm")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CLOSED"))
            .andExpect(
                jsonPath("$.data.closedByMemberId").value(hostMemberId),
            ).andExpect(jsonPath("$.data.closedAt").isNotEmpty())
            .andExpect(jsonPath("$.data.transfers.length()").value(2))
            .andExpect(jsonPath("$.data.missingAccountMemberIds").isEmpty())

        // then: 확정 결과가 각 테이블에 순서대로 저장된다.
        val settlement: MutableMap<String, Any?> =
            jdbc.queryForMap(
                "SELECT id, status, closed_by_member_id, closed_at, version FROM settlements WHERE meeting_id = ?",
                meetingId,
            )
        val settlementId = (settlement.get("id") as Number).toLong()
        assertEquals("CLOSED", settlement.get("status"))
        assertEquals(
            hostMemberId,
            (settlement.get("closed_by_member_id") as Number).toLong(),
        )
        assertEquals(0L, (settlement.get("version") as Number).toLong())
        assertEquals(
            listOf(
                listOf(hostMemberId, 30000L, 12000L),
                listOf(secondMemberId, 6000L, 12000L),
                listOf(thirdMemberId, 0L, 12000L),
            ),
            jdbc.query(
                "SELECT member_id, paid_amount, share_amount FROM settlement_balances" +
                    " WHERE settlement_id = ? ORDER BY balance_order",
                RowMapper { rs, _ ->
                    listOf(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                    )
                },
                settlementId,
            ),
        )
        assertEquals(
            listOf(
                listOf(secondMemberId, hostMemberId, 6000L),
                listOf(thirdMemberId, hostMemberId, 12000L),
            ),
            jdbc.query(
                "SELECT sender_id, recipient_id, amount FROM settlement_transfers" +
                    " WHERE settlement_id = ? ORDER BY transfer_order",
                RowMapper { rs, _ ->
                    listOf(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                    )
                },
                settlementId,
            ),
        )
        assertEquals(
            mutableListOf("국민은행 123-456 모임장"),
            jdbc.query(
                "SELECT CONCAT(bank_name, ' ', account_number, ' ', account_holder)" +
                    " FROM settlement_account_snapshots WHERE settlement_id = ? ORDER BY account_order",
                RowMapper { rs, _ -> rs.getString(1) },
                settlementId,
            ),
        )
        // 정산 커밋 후 별도 트랜잭션으로 참여 중인 모임원 3명에게 알림을 저장한다.
        assertEquals(
            3,
            count(
                "SELECT COUNT(*) FROM notifications WHERE meeting_id = ? AND type = 'SETTLEMENT_CLOSED'",
                meetingId,
            ),
        )

        // 저장된 결과 조회: 보내는 사람에게는 받는 사람의 계좌를 보여준다.
        mvc
            .perform(
                get(url).header(HttpHeaders.AUTHORIZATION, bearer(third)),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.settlementId").value(settlementId))
            .andExpect(jsonPath("$.data.balances.length()").value(3))
            .andExpect(
                jsonPath("$.data.accounts[0].memberId").value(hostMemberId),
            ).andExpect(
                jsonPath("$.data.accounts[0].accountNumber").value("123-456"),
            )
        // 받기만 하는 모임장에게는 보여줄 계좌가 없다.
        mvc
            .perform(
                get(url).header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accounts").isEmpty())

        // 내 정산 내역: 회원3 본인과 관련된 금액·송금만 보여준다.
        mvc
            .perform(
                get("/api/settlements/mine")
                    .header(HttpHeaders.AUTHORIZATION, bearer(third)),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].meetingName").value("정산 통합 테스트"))
            .andExpect(
                jsonPath("$.data[0].result.balances.length()").value(1),
            ).andExpect(
                jsonPath("$.data[0].result.balances[0].memberId")
                    .value(thirdMemberId),
            ).andExpect(
                jsonPath("$.data[0].result.transfers.length()").value(1),
            ).andExpect(
                jsonPath("$.data[0].result.transfers[0].amount").value(12000),
            ).andExpect(
                jsonPath("$.data[0].names." + hostMemberId)
                    .value(host.nickname),
            )

        // 확정 후에는 다시 확정하거나 지출을 추가할 수 없고, 저장된 결과도 바뀌지 않는다.
        assertError(
            mvc.perform(
                post(url + "/confirm")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            409,
        )
        assertError(
            mvc.perform(
                post("/api/meetings/" + meetingId + "/expenses")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        bearer(host),
                    ).contentType(MediaType.APPLICATION_JSON)
                    .content(expenseJson("추가", 1000, mapOf(hostMemberId to 1000L))),
            ),
            409,
        )
        assertEquals(
            0L,
            jdbc.queryForObject(
                "SELECT version FROM settlements WHERE id = ?",
                Long::class.javaObjectType,
                settlementId,
            ),
        )
        assertEquals(
            2,
            count("SELECT COUNT(*) FROM settlement_transfers WHERE settlement_id = ?", settlementId),
        )
    }

    @Test
    fun `확정된 정산이 없으면 결과 조회는 404, 미리보기는 OPEN 정산을 만들지 않는다`() {
        createExpense(host, "식사", 1000, mapOf(hostMemberId to 1000L))

        assertError(
            mvc.perform(
                get(url).header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            404,
        )
        mvc
            .perform(
                get(url + "/preview")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transfers").isEmpty())
        assertEquals(
            0,
            count("SELECT COUNT(*) FROM settlements WHERE meeting_id = ?", meetingId),
        )
    }

    @Test
    fun `모임원이 아니거나 없는 모임이면 거절하고, 잘못된 계좌는 저장하지 않는다`() {
        assertError(
            mvc.perform(
                get(url + "/preview")
                    .header(HttpHeaders.AUTHORIZATION, bearer(outsider)),
            ),
            403,
        )
        assertError(
            mvc.perform(
                get("/api/meetings/" + Long.MAX_VALUE + "/settlement/preview")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            404,
        )

        assertError(
            mvc.perform(
                put(url + "/account")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(
                        MediaType.APPLICATION_JSON,
                    ).content(
                        """
                        {"bankName":"국민은행","accountNumber":"12-ab","accountHolder":"모임장"}
                        
                        """.trimIndent(),
                    ),
            ),
            400,
        )
        assertEquals(
            0,
            count("SELECT COUNT(*) FROM settlement_accounts WHERE meeting_id = ?", meetingId),
        )
    }

    @Test
    fun `계좌 필드가 없거나 null이면 역직렬화가 아닌 요청 검증에서 공백 입력과 같은 400 응답을 반환한다`() {
        val blank =
            accountError(
                """
                {"bankName":" ","accountNumber":"123-456","accountHolder":"모임장"}
                
                """.trimIndent(),
            )
        assertEquals(
            blank,
            accountError(
                """
                {"accountNumber":"123-456","accountHolder":"모임장"}
                
                """.trimIndent(),
            ),
        )
        assertEquals(
            blank,
            accountError(
                """
                {"bankName":null,"accountNumber":"123-456","accountHolder":"모임장"}
                
                """.trimIndent(),
            ),
        )
        assertEquals(
            0,
            count("SELECT COUNT(*) FROM settlement_accounts WHERE meeting_id = ?", meetingId),
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `총금액 또는 개인 부담액이 소수이면 400을 반환하고 지출을 저장하지 않는다`(fractionalTotal: Boolean) {
        val amount = if (fractionalTotal) "10000.5" else "10000"
        val share = if (fractionalTotal) "10000" else "10000.5"
        val body: String =
            """
            {"title":"소수 금액","amount":%s,"splitMode":"EXACT","participants":[
                {"memberId":%d,"amount":%s}]}
            
            """.trimIndent().format(amount, hostMemberId, share)

        assertError(
            mvc.perform(
                post("/api/meetings/" + meetingId + "/expenses")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ),
            400,
        )
        assertEquals(
            0,
            count("SELECT COUNT(*) FROM expenses WHERE meeting_id = ?", meetingId),
        )
    }

    @Test
    fun `Int 범위를 넘는 금액이 지출 등록부터 정산 저장·재조회까지 유지된다`() {
        // 송금액 자체도 Int 범위를 넘도록 결제자는 부담하지 않는다.
        createExpense(
            host,
            "큰 금액",
            3000000001L,
            mapOf(secondMemberId to 3000000001L),
        )

        assertEquals(
            3000000001L,
            jdbc.queryForObject(
                "SELECT amount FROM expenses WHERE meeting_id = ?",
                Long::class.javaObjectType,
                meetingId,
            ),
        )
        assertEquals(
            3000000001L,
            jdbc.queryForObject(
                "SELECT share_amount FROM expense_shares WHERE expense_id IN" +
                    " (SELECT id FROM expenses WHERE meeting_id = ?)",
                Long::class.javaObjectType,
                meetingId,
            ),
        )

        assertSettlementAmounts(
            listOf(
                listOf(hostMemberId, 3000000001L, 0L),
                listOf(secondMemberId, 0L, 3000000001L),
                listOf(thirdMemberId, 0L, 0L),
            ),
            listOf(listOf(secondMemberId, hostMemberId, 3000000001L)),
        )
    }

    @Test
    fun `균등 분담의 단위·차액 담당자가 지출 저장과 최종 정산에 그대로 반영된다`() {
        mvc
            .perform(
                post("/api/meetings/" + meetingId + "/expenses")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"title":"균등 분담","amount":10000,"splitMode":"EQUAL","roundingUnit":100,
                         "remainderMemberId":%d,"randomRemainder":false,"participants":[
                            {"memberId":%d},{"memberId":%d},{"memberId":%d}]}
                        
                        """.trimIndent()
                            .format(thirdMemberId, hostMemberId, secondMemberId, thirdMemberId),
                    ),
            ).andExpect(status().isCreated())
            .andExpect(
                jsonPath("$.data.remainderMemberId").value(thirdMemberId),
            ).andExpect(jsonPath("$.data.participants.length()").value(3))
            .andExpect(
                jsonPath("$.data.participants[0].memberId")
                    .value(hostMemberId),
            ).andExpect(jsonPath("$.data.participants[0].amount").value(3300))
            .andExpect(
                jsonPath("$.data.participants[1].memberId")
                    .value(secondMemberId),
            ).andExpect(jsonPath("$.data.participants[1].amount").value(3300))
            .andExpect(
                jsonPath("$.data.participants[2].memberId")
                    .value(thirdMemberId),
            ).andExpect(jsonPath("$.data.participants[2].amount").value(3400))
        assertEquals(
            listOf(
                listOf(hostMemberId, 3300L),
                listOf(secondMemberId, 3300L),
                listOf(thirdMemberId, 3400L),
            ),
            jdbc.query(
                "SELECT member_id, share_amount FROM expense_shares WHERE expense_id IN" +
                    " (SELECT id FROM expenses WHERE meeting_id = ?) ORDER BY member_id",
                RowMapper { rs, _ ->
                    listOf(
                        rs.getLong(1),
                        rs.getLong(2),
                    )
                },
                meetingId,
            ),
        )

        assertSettlementAmounts(
            listOf(
                listOf(hostMemberId, 10000L, 3300L),
                listOf(secondMemberId, 0L, 3300L),
                listOf(thirdMemberId, 0L, 3400L),
            ),
            listOf(
                listOf(secondMemberId, hostMemberId, 3300L),
                listOf(thirdMemberId, hostMemberId, 3400L),
            ),
        )
    }

    @Test
    fun `지출 수정으로 바뀐 총금액·참여자·옵션이 저장되고 최종 정산에도 반영된다`() {
        createExpense(
            host,
            "수정 전",
            300,
            mapOf(hostMemberId to 100L, secondMemberId to 200L),
        )
        val expenseId: Long =
            assertNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM expenses WHERE meeting_id = ?",
                    Long::class.javaObjectType,
                    meetingId,
                ),
            )
        val createdAt: Any? =
            jdbc.queryForObject(
                "SELECT created_at FROM expenses WHERE id = ?",
                Any::class.java,
                expenseId,
            )
        // EQUAL → EXACT 전환에서 기존 차액 옵션도 제거되는지 확인한다.
        mvc
            .perform(
                put(expenseUrl() + "/" + expenseId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"title":"균등 수정","amount":10000,"splitMode":"EQUAL","roundingUnit":100,
                         "remainderMemberId":%d,"randomRemainder":false,"participants":[{"memberId":%d},{"memberId":%d},{"memberId":%d}]}
                        
                        """.trimIndent()
                            .format(thirdMemberId, hostMemberId, secondMemberId, thirdMemberId),
                    ),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.amount").value(10000))
        mvc
            .perform(
                put(expenseUrl() + "/" + expenseId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(expenseJson("수정 후", 500, mapOf(thirdMemberId to 500L))),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(expenseId))
            .andExpect(jsonPath("$.data.payerMemberId").value(hostMemberId))
            .andExpect(jsonPath("$.data.amount").value(500))
            .andExpect(jsonPath("$.data.splitMode").value("EXACT"))
            .andExpect(jsonPath("$.data.roundingUnit").doesNotExist())
            .andExpect(jsonPath("$.data.remainderMemberId").doesNotExist())
        mvc
            .perform(
                get(expenseUrl() + "/" + expenseId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.amount").value(500))
            .andExpect(jsonPath("$.data.participants.length()").value(1))
            .andExpect(
                jsonPath("$.data.participants[0].memberId")
                    .value(thirdMemberId),
            ).andExpect(jsonPath("$.data.participants[0].amount").value(500))
        assertEquals(
            500L,
            jdbc.queryForObject(
                "SELECT amount FROM expenses WHERE id = ?",
                Long::class.javaObjectType,
                expenseId,
            ),
        )
        assertEquals(
            createdAt,
            jdbc.queryForObject(
                "SELECT created_at FROM expenses WHERE id = ?",
                Any::class.java,
                expenseId,
            ),
        )
        assertEquals(
            listOf(
                listOf(thirdMemberId, 500L),
            ),
            jdbc.query(
                "SELECT member_id, share_amount FROM expense_shares WHERE expense_id = ?",
                RowMapper { rs, _ ->
                    listOf(
                        rs.getLong(1),
                        rs.getLong(2),
                    )
                },
                expenseId,
            ),
        )
        assertEquals(
            listOf(thirdMemberId),
            jdbc.queryForList<Long>(
                "SELECT member_id FROM expense_participants WHERE expense_id = ?",
                Long::class.javaObjectType,
                expenseId,
            ),
        )
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM expenses WHERE id = ? AND rounding_unit IS NULL" +
                    " AND remainder_member_id IS NULL AND split_mode = 'EXACT'",
                expenseId,
            ),
        )
        assertSettlementAmounts(
            listOf(
                listOf(hostMemberId, 500L, 0L),
                listOf(secondMemberId, 0L, 0L),
                listOf(thirdMemberId, 0L, 500L),
            ),
            listOf(listOf(thirdMemberId, hostMemberId, 500L)),
        )
    }

    @Test
    fun `같은 요청 번호로 재등록해도 같은 지출을 반환하고 정산 금액은 한 번만 반영된다`() {
        val key = UUID.randomUUID().toString()
        val body = expenseJson("재시도", 10001, mapOf(secondMemberId to 10001L))
        register(key, body)
        val expenseId = onlyExpenseId()
        register(key.uppercase(), body)
            .andExpect(jsonPath("$.data.id").value(expenseId))
        assertSingleRegistration(expenseId, 10001)
        assertSettlementAmounts(
            listOf(
                listOf(hostMemberId, 10001L, 0L),
                listOf(secondMemberId, 0L, 10001L),
                listOf(thirdMemberId, 0L, 0L),
            ),
            listOf(listOf(secondMemberId, hostMemberId, 10001L)),
        )
        // 확정 이후 도착한 동일 요청도 새 지출을 만들거나 재계산하지 않는다.
        register(key, body).andExpect(jsonPath("$.data.id").value(expenseId))
        assertSingleRegistration(expenseId, 10001)
    }

    @Test
    fun `같은 요청 번호의 내용이 바뀌면 409이고 원래 지출·분담액을 유지한다`() {
        val key = UUID.randomUUID().toString()
        register(key, expenseJson("원본", 100, mapOf(secondMemberId to 100L)))
        val expenseId = onlyExpenseId()
        assertError(
            mvc.perform(
                post(expenseUrl())
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(expenseJson("변경", 200, mapOf(thirdMemberId to 200L))),
            ),
            409,
        )
        assertSingleRegistration(expenseId, 100)
        assertEquals(
            listOf(
                listOf(secondMemberId, 100L),
            ),
            jdbc.query(
                "SELECT member_id, share_amount FROM expense_shares WHERE expense_id = ?",
                RowMapper { rs, _ ->
                    listOf(
                        rs.getLong(1),
                        rs.getLong(2),
                    )
                },
                expenseId,
            ),
        )
    }

    @Test
    fun `등록 후 삭제한 지출의 요청 번호를 재사용하면 410이고 지출이 부활하지 않는다`() {
        val key = UUID.randomUUID().toString()
        val body = expenseJson("삭제", 100, mapOf(secondMemberId to 100L))
        register(key, body)
        val expenseId = onlyExpenseId()
        mvc
            .perform(
                delete(expenseUrl() + "/" + expenseId).header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(host),
                ),
            ).andExpect(status().isNoContent())
        assertError(
            mvc.perform(
                post(expenseUrl())
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ),
            410,
        )
        assertEquals(
            0,
            count("SELECT COUNT(*) FROM expenses WHERE meeting_id = ?", meetingId),
        )
        assertEquals(
            0,
            count("SELECT COUNT(*) FROM expense_shares WHERE expense_id = ?", expenseId),
        )
        assertEquals(
            0,
            count("SELECT COUNT(*) FROM expense_participants WHERE expense_id = ?", expenseId),
        )
        assertEquals(
            1,
            count("SELECT COUNT(*) FROM expense_registrations WHERE meeting_id = ?", meetingId),
        )
    }

    @Test
    fun `같은 요청 번호로 동시에 등록해도 지출·요청 기록은 한 건이고 랜덤 차액도 동일하다`() {
        val key = UUID.randomUUID().toString()
        val body: String =
            """
            {"title":"동시 재시도","amount":10000,"splitMode":"EQUAL","roundingUnit":100,
             "randomRemainder":true,"participants":[{"memberId":%d},{"memberId":%d},{"memberId":%d}]}
            
            """.trimIndent().format(hostMemberId, secondMemberId, thirdMemberId)
        val authorization = bearer(host)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        var firstData: Map<String, Any?>
        Executors.newFixedThreadPool(2).use { executor ->
            val request =
                Callable {
                    ready.countDown()
                    assertTrue(start.await(10, TimeUnit.SECONDS), "동시 등록 시작 시간 초과")
                    mvc
                        .perform(
                            post(expenseUrl())
                                .header(HttpHeaders.AUTHORIZATION, authorization)
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        ).andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                }
            val first = executor.submit(request)
            val secondRequest = executor.submit(request)
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS), "동시 등록 준비 시간 초과")
                start.countDown()
                firstData =
                    JsonPath.read<MutableMap<String, Any?>>(
                        first.get(20, TimeUnit.SECONDS),
                        "$.data",
                    )
                val secondData =
                    JsonPath.read<MutableMap<String, Any?>>(
                        secondRequest.get(
                            20,
                            TimeUnit.SECONDS,
                        ),
                        "$.data",
                    )
                // 최초 응답과 DB 재조회 응답의 시각 정밀도 차이에 의존하지 않는다.
                for (field in mutableListOf(
                    "id",
                    "amount",
                    "payerMemberId",
                    "splitMode",
                    "roundingUnit",
                    "remainderMemberId",
                    "participants",
                )) {
                    assertEquals(
                        firstData.get(field),
                        secondData.get(field),
                        "재시도 응답의 " + field,
                    )
                }
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        }
        val expenseId = onlyExpenseId()
        assertEquals(expenseId, (firstData.get("id") as Number).toLong())
        assertSingleRegistration(expenseId, 10000)
        val remainderMemberId: Long? =
            jdbc.queryForObject(
                "SELECT remainder_member_id FROM expenses WHERE id = ?",
                Long::class.javaObjectType,
                expenseId,
            )
        assertEquals(
            remainderMemberId,
            (firstData.get("remainderMemberId") as Number).toLong(),
        )
        assertTrue(
            listOf(hostMemberId, secondMemberId, thirdMemberId).contains(remainderMemberId),
        )
        assertEquals(
            3400L,
            jdbc.queryForObject(
                "SELECT share_amount FROM expense_shares WHERE expense_id = ? AND member_id = ?",
                Long::class.javaObjectType,
                expenseId,
                remainderMemberId,
            ),
        )
    }

    @Test
    fun `동시 확정 요청은 모임 잠금을 기다린 뒤 한 건만 성공하고 결과·알림을 한 번만 저장한다`() {
        prepareSettlementRace()

        runWithFirstTransactionHeld(
            CheckedRequest { confirmRequest().andExpect(status().isOk()) },
            CheckedRequest { assertError(confirmRequest(), 409) },
        )

        assertConfirmedAmounts(300, 100, 200)
    }

    @ParameterizedTest(name = "{0}, 확정 선행={1}")
    @CsvSource(
        "CREATE,true",
        "CREATE,false",
        "UPDATE,true",
        "UPDATE,false",
        "DELETE,true",
        "DELETE,false",
    )
    fun `지출 변경과 확정은 모임 잠금으로 직렬화하고, 먼저 커밋한 상태를 반영한다`(
        operation: String,
        confirmFirst: Boolean,
    ) {
        prepareSettlementRace()
        val expenseId = onlyExpenseId()
        val confirm =
            CheckedRequest { confirmRequest().andExpect(status().isOk()) }
        val change =
            CheckedRequest {
                val result = changeExpense(operation, expenseId)
                if (confirmFirst) {
                    assertError(result, 409)
                } else {
                    result.andExpect(
                        if (operation == "CREATE") {
                            status().isCreated()
                        } else {
                            if (operation == "DELETE") {
                                status()
                                    .isNoContent()
                            } else {
                                status().isOk()
                            }
                        },
                    )
                }
            }

        runWithFirstTransactionHeld(
            if (confirmFirst) confirm else change,
            if (confirmFirst) change else confirm,
        )

        if (confirmFirst) {
            assertConfirmedAmounts(300, 100, 200)
            assertEquals(
                1,
                count("SELECT COUNT(*) FROM expenses WHERE meeting_id = ?", meetingId),
            )
        } else {
            when (operation) {
                "CREATE" -> {
                    assertConfirmedAmounts(900, 100, 800)
                    assertEquals(
                        2,
                        count("SELECT COUNT(*) FROM expenses WHERE meeting_id = ?", meetingId),
                    )
                }

                "UPDATE" -> assertConfirmedAmounts(500, 0, 500)
                "DELETE" -> {
                    assertConfirmedAmounts(0, 0, 0)
                    assertEquals(
                        0,
                        count("SELECT COUNT(*) FROM expenses WHERE meeting_id = ?", meetingId),
                    )
                }

                else -> throw IllegalArgumentException(operation)
            }
        }
    }

    @ParameterizedTest(name = "확정 선행={0}")
    @ValueSource(booleans = [true, false])
    fun `계좌 변경이 먼저면 변경 계좌를 확정하고, 확정이 먼저면 계좌 변경을 거절한다`(confirmFirst: Boolean) {
        prepareSettlementRace()
        val confirm =
            CheckedRequest { confirmRequest().andExpect(status().isOk()) }
        val change =
            CheckedRequest {
                val result = saveAccount(host, "999")
                if (confirmFirst) {
                    assertError(result, 409)
                } else {
                    result.andExpect(status().isOk())
                }
            }

        runWithFirstTransactionHeld(
            if (confirmFirst) confirm else change,
            if (confirmFirst) change else confirm,
        )

        val expected = if (confirmFirst) "123-456" else "999"
        assertEquals(
            expected,
            jdbc.queryForObject(
                "SELECT account_number FROM settlement_accounts WHERE meeting_id = ? AND member_id = ?",
                String::class.java,
                meetingId,
                hostMemberId,
            ),
        )
        val id = settlementId()
        assertEquals(
            listOf(expected),
            jdbc.queryForList<String>(
                "SELECT account_number FROM settlement_account_snapshots WHERE settlement_id = ?",
                String::class.java,
                id,
            ),
        )
        mvc
            .perform(
                get(url).header(HttpHeaders.AUTHORIZATION, bearer(second)),
            ).andExpect(status().isOk())
            .andExpect(
                jsonPath("$.data.accounts[0].accountNumber").value(expected),
            )
        assertConfirmedAmounts(300, 100, 200)
    }

    @Test
    fun `기존 CLOSED 정산의 미등록 수취인만 계좌를 한 번 보충하며 기존 결과와 스냅샷은 변경하지 않는다`() {
        saveAccount(host, "123-456").andExpect(status().isOk())
        // 신규 확정 API는 계좌 누락을 거절하므로, 복구 대상인 기존 CLOSED 데이터를 직접 준비한다.
        transaction.executeWithoutResult { _ ->
            val legacy = Settlement(meetingId)
            legacy.saveResult(
                listOf(
                    SettlementBalance(hostMemberId, 300, 100),
                    SettlementBalance(secondMemberId, 300, 100),
                    SettlementBalance(thirdMemberId, 0, 400),
                ),
                listOf(
                    SettlementTransfer(thirdMemberId, hostMemberId, 200),
                    SettlementTransfer(thirdMemberId, secondMemberId, 200),
                ),
            )
            legacy.saveAccounts(
                listOf(
                    SettlementAccountSnapshot(
                        assertNotNull(
                            accounts.findByMeetingIdAndMemberId(
                                meetingId,
                                hostMemberId,
                            ),
                        ),
                    ),
                ),
            )
            legacy.close(hostMemberId)
            settlements.saveAndFlush(legacy)
        }
        val id = settlementId()
        val closed: MutableMap<String, Any?> =
            jdbc.queryForMap(
                "SELECT status, closed_by_member_id, closed_at FROM settlements WHERE id = ?",
                id,
            )
        val version: Long =
            assertNotNull(
                jdbc.queryForObject(
                    "SELECT version FROM settlements WHERE id = ?",
                    Long::class.javaObjectType,
                    id,
                ),
            )
        val balances: List<Map<String, Any?>> =
            jdbc.queryForList(
                "SELECT * FROM settlement_balances WHERE settlement_id = ? ORDER BY balance_order",
                id,
            )
        val transfers: List<Map<String, Any?>> =
            jdbc.queryForList(
                "SELECT * FROM settlement_transfers WHERE settlement_id = ? ORDER BY transfer_order",
                id,
            )
        val existingSnapshot: MutableMap<String, Any?> =
            jdbc.queryForMap(
                "SELECT * FROM settlement_account_snapshots WHERE settlement_id = ?",
                id,
            )

        mvc
            .perform(
                get(url).header(HttpHeaders.AUTHORIZATION, bearer(third)),
            ).andExpect(status().isOk())
            .andExpect(
                jsonPath("$.data.missingAccountMemberIds[0]")
                    .value(secondMemberId),
            )
        saveAccount(second, "222").andExpect(status().isOk())

        assertEquals(
            version + 1,
            jdbc.queryForObject(
                "SELECT version FROM settlements WHERE id = ?",
                Long::class.javaObjectType,
                id,
            ),
        )
        assertEquals(
            existingSnapshot,
            jdbc.queryForMap(
                "SELECT * FROM settlement_account_snapshots WHERE settlement_id = ? AND member_id = ?",
                id,
                hostMemberId,
            ),
        )
        assertEquals(
            "222",
            jdbc.queryForObject(
                "SELECT account_number FROM settlement_accounts WHERE meeting_id = ? AND member_id = ?",
                String::class.java,
                meetingId,
                secondMemberId,
            ),
        )
        mvc
            .perform(
                get(url).header(HttpHeaders.AUTHORIZATION, bearer(third)),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.missingAccountMemberIds").isEmpty())
            .andExpect(jsonPath("$.data.accounts.length()").value(2))
            .andExpect(
                jsonPath("$.data.accounts[1].memberId").value(secondMemberId),
            ).andExpect(
                jsonPath("$.data.accounts[1].accountNumber").value("222"),
            )

        val snapshots: List<Map<String, Any?>> =
            jdbc.queryForList(
                "SELECT * FROM settlement_account_snapshots WHERE settlement_id = ? ORDER BY account_order",
                id,
            )
        assertError(saveAccount(second, "333"), 409) // 보충한 계좌의 재등록
        assertError(saveAccount(host, "444"), 409) // 기존 스냅샷 변경
        assertError(saveAccount(third, "555"), 409) // 수취인이 아닌 회원

        assertEquals(
            closed,
            jdbc.queryForMap(
                "SELECT status, closed_by_member_id, closed_at FROM settlements WHERE id = ?",
                id,
            ),
        )
        assertEquals(
            version + 1,
            jdbc.queryForObject(
                "SELECT version FROM settlements WHERE id = ?",
                Long::class.javaObjectType,
                id,
            ),
        )
        assertEquals(
            balances,
            jdbc.queryForList(
                "SELECT * FROM settlement_balances WHERE settlement_id = ? ORDER BY balance_order",
                id,
            ),
        )
        assertEquals(
            transfers,
            jdbc.queryForList(
                "SELECT * FROM settlement_transfers WHERE settlement_id = ? ORDER BY transfer_order",
                id,
            ),
        )
        assertEquals(
            snapshots,
            jdbc.queryForList(
                "SELECT * FROM settlement_account_snapshots WHERE settlement_id = ? ORDER BY account_order",
                id,
            ),
        )
        assertEquals(
            mutableListOf("123-456", "222"),
            jdbc.queryForList<String>(
                "SELECT account_number FROM settlement_accounts WHERE meeting_id = ? ORDER BY member_id",
                String::class.java,
                meetingId,
            ),
        )
        assertEquals(
            0,
            count(
                "SELECT COUNT(*) FROM notifications WHERE meeting_id = ? AND type = 'SETTLEMENT_CLOSED'",
                meetingId,
            ),
        )
    }

    private fun prepareSettlementRace() {
        createExpense(
            host,
            "원본 지출",
            300,
            mapOf(hostMemberId to 100L, secondMemberId to 200L),
        )
        saveAccount(host, "123-456").andExpect(status().isOk())
    }

    private fun confirmRequest(): ResultActions =
        mvc.perform(
            post(url + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, bearer(host)),
        )

    private fun saveAccount(
        user: User,
        number: String?,
    ): ResultActions =
        mvc.perform(
            put(url + "/account")
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(
                    MediaType.APPLICATION_JSON,
                ).content(
                    """
                    {"bankName":"은행","accountNumber":"%s","accountHolder":"예금주"}
                    
                    """.trimIndent().format(number),
                ),
        )

    private fun changeExpense(
        operation: String,
        expenseId: Long,
    ): ResultActions {
        val request =
            when (operation) {
                "CREATE" ->
                    post(expenseUrl())
                        .contentType(
                            MediaType.APPLICATION_JSON,
                        ).content(expenseJson("추가", 600, mapOf(secondMemberId to 600L)))

                "UPDATE" ->
                    put(expenseUrl() + "/" + expenseId)
                        .contentType(
                            MediaType.APPLICATION_JSON,
                        ).content(expenseJson("수정", 500, mapOf(secondMemberId to 500L)))

                "DELETE" -> delete(expenseUrl() + "/" + expenseId)
                else -> throw IllegalArgumentException(operation)
            }
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(host)))
    }

    private fun settlementId(): Long =
        assertNotNull(
            jdbc.queryForObject(
                "SELECT id FROM settlements WHERE meeting_id = ?",
                Long::class.javaObjectType,
                meetingId,
            ),
        )

    private fun assertConfirmedAmounts(
        paid: Long,
        hostShare: Long,
        secondShare: Long,
    ) {
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM settlements WHERE meeting_id = ? AND status = 'CLOSED'",
                meetingId,
            ),
        )
        val id = settlementId()
        val balances =
            listOf(
                listOf(hostMemberId, paid, hostShare),
                listOf(secondMemberId, 0L, secondShare),
                listOf(thirdMemberId, 0L, 0L),
            )
        val transfers: List<List<Long>> =
            if (secondShare == 0L) {
                mutableListOf()
            } else {
                listOf(
                    listOf(secondMemberId, hostMemberId, secondShare),
                )
            }
        assertResponseAmounts(
            mvc.perform(
                get(url)
                    .header(HttpHeaders.AUTHORIZATION, bearer(second)),
            ),
            balances,
            transfers,
        )
        assertEquals(
            balances,
            jdbc.query(
                "SELECT member_id, paid_amount, share_amount FROM settlement_balances WHERE settlement_id = ? ORDER BY balance_order",
                RowMapper { rs, _ ->
                    listOf(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                    )
                },
                id,
            ),
        )
        assertEquals(
            transfers,
            jdbc.query(
                "SELECT sender_id, recipient_id, amount FROM settlement_transfers WHERE settlement_id = ? ORDER BY transfer_order",
                RowMapper { rs, _ ->
                    listOf(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                    )
                },
                id,
            ),
        )
        assertEquals(
            paid,
            jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE meeting_id = ?",
                Long::class.javaObjectType,
                meetingId,
            ),
        )
        assertEquals(
            3,
            count(
                "SELECT COUNT(*) FROM notifications WHERE meeting_id = ? AND type = 'SETTLEMENT_CLOSED'",
                meetingId,
            ),
        )
        assertEquals(
            3,
            count(
                "SELECT COUNT(DISTINCT receiver_id) FROM notifications WHERE meeting_id = ? AND type = 'SETTLEMENT_CLOSED'",
                meetingId,
            ),
        )
    }

    // 첫 요청만 외부 트랜잭션으로 감싸 실제 모임 잠금을 커밋 직전까지 유지한다.
    // 둘째 요청은 다른 스레드의 독립 트랜잭션으로 실행하며, 첫 커밋 전에는 완료되지 않아야 한다.
    private fun runWithFirstTransactionHeld(
        firstRequest: CheckedRequest,
        secondRequest: CheckedRequest,
    ) {
        val firstCompleted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first =
                executor.submit(
                    Runnable {
                        transaction.executeWithoutResult { _ ->
                            try {
                                firstRequest.perform()
                                firstCompleted.countDown()
                                assertTrue(
                                    releaseFirst.await(20, TimeUnit.SECONDS),
                                    "첫 트랜잭션 커밋 대기 시간 초과",
                                )
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw AssertionError(e)
                            } catch (e: Exception) {
                                throw AssertionError(e)
                            }
                        }
                    },
                )
            assertTrue(firstCompleted.await(20, TimeUnit.SECONDS), "첫 요청 처리 시간 초과")
            val second =
                executor.submit(
                    Callable {
                        secondStarted.countDown()
                        secondRequest.perform()
                        null
                    },
                )
            assertTrue(secondStarted.await(20, TimeUnit.SECONDS), "둘째 요청 시작 시간 초과")
            assertThrows<TimeoutException>("첫 트랜잭션 커밋 전에 둘째 요청이 완료됐습니다.") { second.get(300, TimeUnit.MILLISECONDS) }
            releaseFirst.countDown()
            first.get(20, TimeUnit.SECONDS)
            second.get(20, TimeUnit.SECONDS)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            assertTrue(
                executor.awaitTermination(20, TimeUnit.SECONDS),
                "동시 요청 스레드 종료 시간 초과",
            )
        }
    }

    private fun interface CheckedRequest {
        fun perform()
    }

    private fun expenseUrl(): String = "/api/meetings/" + meetingId + "/expenses"

    private fun register(
        key: String,
        body: String,
    ): ResultActions =
        mvc
            .perform(
                post(expenseUrl())
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated())

    private fun onlyExpenseId(): Long =
        assertNotNull(
            jdbc.queryForObject(
                "SELECT id FROM expenses WHERE meeting_id = ?",
                Long::class.javaObjectType,
                meetingId,
            ),
        )

    private fun assertSingleRegistration(
        expenseId: Long,
        amount: Long,
    ) {
        assertEquals(
            1,
            count("SELECT COUNT(*) FROM expenses WHERE meeting_id = ?", meetingId),
        )
        assertEquals(
            amount,
            jdbc.queryForObject(
                "SELECT amount FROM expenses WHERE id = ?",
                Long::class.javaObjectType,
                expenseId,
            ),
        )
        assertEquals(
            amount,
            jdbc.queryForObject(
                "SELECT SUM(share_amount) FROM expense_shares WHERE expense_id = ?",
                Long::class.javaObjectType,
                expenseId,
            ),
        )
        assertEquals(
            1,
            count("SELECT COUNT(*) FROM expense_registrations WHERE meeting_id = ?", meetingId),
        )
        assertEquals(
            expenseId,
            jdbc.queryForObject(
                "SELECT expense_id FROM expense_registrations WHERE meeting_id = ?",
                Long::class.javaObjectType,
                meetingId,
            ),
        )
    }

    private fun assertSettlementAmounts(
        balances: List<List<Long>>,
        transfers: List<List<Long>>,
    ) {
        assertResponseAmounts(
            mvc.perform(
                get(url + "/preview").header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(host),
                ),
            ),
            balances,
            transfers,
        )
        mvc
            .perform(
                put(url + "/account")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(
                        MediaType.APPLICATION_JSON,
                    ).content(
                        """
                        {"bankName":"국민은행","accountNumber":"123-456","accountHolder":"모임장"}
                        
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk())
        assertResponseAmounts(
            mvc.perform(
                post(url + "/confirm").header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(host),
                ),
            ),
            balances,
            transfers,
        )

        val settlementId: Long =
            assertNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM settlements WHERE meeting_id = ?",
                    Long::class.javaObjectType,
                    meetingId,
                ),
            )
        assertEquals(
            balances,
            jdbc.query(
                "SELECT member_id, paid_amount, share_amount FROM settlement_balances" +
                    " WHERE settlement_id = ? ORDER BY balance_order",
                RowMapper { rs, _ ->
                    listOf(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                    )
                },
                settlementId,
            ),
        )
        assertEquals(
            transfers,
            jdbc.query(
                "SELECT sender_id, recipient_id, amount FROM settlement_transfers" +
                    " WHERE settlement_id = ? ORDER BY transfer_order",
                RowMapper { rs, _ ->
                    listOf(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                    )
                },
                settlementId,
            ),
        )
        // 별도 HTTP 요청으로 저장된 결과를 다시 읽어 응답 변환까지 확인한다.
        assertResponseAmounts(
            mvc.perform(
                get(url)
                    .header(HttpHeaders.AUTHORIZATION, bearer(second)),
            ),
            balances,
            transfers,
        )
    }

    private fun assertResponseAmounts(
        result: ResultActions,
        balances: List<List<Long>>,
        transfers: List<List<Long>>,
    ) {
        result
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.data.balances.length()").value(balances.size),
            ).andExpect(
                jsonPath("$.data.transfers.length()").value(transfers.size),
            )
        for (index in balances.indices) {
            val balance = balances[index]
            val path = "$.data.balances[" + index + "]"
            result
                .andExpect(
                    jsonPath(path + ".memberId").value(
                        Matchers.equalTo(balance[0]),
                        Long::class.javaObjectType,
                    ),
                ).andExpect(
                    jsonPath(path + ".paidAmount")
                        .value(Matchers.equalTo(balance[1]), Long::class.javaObjectType),
                ).andExpect(
                    jsonPath(path + ".shareAmount")
                        .value(Matchers.equalTo(balance[2]), Long::class.javaObjectType),
                )
        }
        for (index in transfers.indices) {
            val transfer = transfers[index]
            val path = "$.data.transfers[" + index + "]"
            result
                .andExpect(
                    jsonPath(path + ".senderId").value(
                        Matchers.equalTo(transfer[0]),
                        Long::class.javaObjectType,
                    ),
                ).andExpect(
                    jsonPath(path + ".recipientId")
                        .value(Matchers.equalTo(transfer[1]), Long::class.javaObjectType),
                ).andExpect(
                    jsonPath(path + ".amount")
                        .value(Matchers.equalTo(transfer[2]), Long::class.javaObjectType),
                )
        }
    }

    private fun createExpense(
        payer: User,
        title: String,
        amount: Long,
        shares: Map<Long, Long>,
    ) {
        mvc
            .perform(
                post("/api/meetings/" + meetingId + "/expenses")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        bearer(payer),
                    ).contentType(MediaType.APPLICATION_JSON)
                    .content(expenseJson(title, amount, shares)),
            ).andExpect(status().isCreated())
    }

    private fun expenseJson(
        title: String,
        amount: Long,
        shares: Map<Long, Long>,
    ): String {
        val participants =
            shares.entries
                .map { share -> "{\"memberId\":" + share.key + ",\"amount\":" + share.value + "}" }
                .toList()
                .joinToString(",")
        return (
            "{\"title\":\"" + title + "\",\"amount\":" + amount +
                ",\"splitMode\":\"EXACT\",\"randomRemainder\":false,\"participants\":[" + participants + "]}"
        )
    }

    private fun bearer(user: User): String = "Bearer " + tokens.createAccessToken(user.id)

    private fun count(
        sql: String,
        vararg args: Any?,
    ): Int = assertNotNull(jdbc.queryForObject(sql, Int::class.javaObjectType, *args))

    private fun accountError(body: String): String {
        val result =
            mvc.perform(
                put(url + "/account")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
        assertError(result, 400)
        return assertNotNull(
            JsonPath.read<String>(
                result.andReturn().getResponse().getContentAsString(),
                "$.message",
            ),
        )
    }

    private fun assertError(
        result: ResultActions,
        code: Int,
    ) {
        result
            .andExpect(status().`is`(code))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(code))
    }
}
