package com.prgms.backend.domain.settlement;

import com.prgms.backend.domain.meeting.entity.Meeting;
import com.prgms.backend.domain.meeting.entity.MeetingMember;
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository;
import com.prgms.backend.domain.meeting.repository.MeetingRepository;
import com.prgms.backend.domain.user.entity.User;
import com.prgms.backend.domain.user.repository.UserRepository;
import com.prgms.backend.security.JwtTokenProvider;
import com.prgms.backend.support.MySqlContainerSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// JWT 인증부터 Controller, Service, Repository, MySQL(Flyway 스키마)까지 mock 없이 함께 검증한다.
// 테스트 트랜잭션으로 감싸지 않아 실제 커밋과 커밋 후 알림 저장까지 확인하고, DB 값은 JdbcTemplate으로 직접 조회한다.
// 데이터가 롤백되지 않으므로 매 테스트마다 새 회원·모임을 만든다.
@SpringBootTest
@AutoConfigureMockMvc
class SettlementApiIntegrationTest extends MySqlContainerSupport {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private UserRepository users;
    @Autowired private MeetingRepository meetings;
    @Autowired private MeetingMemberRepository members;
    @Autowired private JdbcTemplate jdbc;

    private User host;
    private User second;
    private User third;
    private User outsider;
    private long meetingId;
    private long hostMemberId;
    private long secondMemberId;
    private long thirdMemberId;
    private String url;

    @BeforeEach
    void setUp() {
        // 준비 데이터만 저장소로 만들고, 검증할 동작은 모두 API로 호출한다.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        host = users.save(new User("host-" + suffix + "@test.com", "모임장-" + suffix, "password"));
        second = users.save(new User("second-" + suffix + "@test.com", "회원2-" + suffix, "password"));
        third = users.save(new User("third-" + suffix + "@test.com", "회원3-" + suffix, "password"));
        outsider = users.save(new User("outsider-" + suffix + "@test.com", "외부인-" + suffix, "password"));
        Meeting meeting = meetings.save(new Meeting(host, "정산 통합 테스트", null));
        meetingId = meeting.getId();
        // 저장 순서대로 모임원 ID가 커지므로 송금 순서(보내는 사람 ID 오름차순)를 예상할 수 있다.
        hostMemberId = members.save(new MeetingMember(meeting, host)).getId();
        secondMemberId = members.save(new MeetingMember(meeting, second)).getId();
        thirdMemberId = members.save(new MeetingMember(meeting, third)).getId();
        url = "/api/meetings/" + meetingId + "/settlement";
    }

    @Test
    @DisplayName("지출 등록 → 미리보기 → 계좌 등록 → 확정 → 조회까지 API 응답과 DB 저장 값이 일치한다")
    void confirmFlow() throws Exception {
        // given: 모임장이 30,000원, 두 번째 회원이 6,000원을 결제하고 셋이 같은 금액을 부담
        // 결제액 [모임장 30,000 / 회원2 6,000 / 회원3 0], 부담액 각 12,000
        // → 회원2가 6,000원, 회원3이 12,000원을 모임장에게 보낸다.
        createExpense(host, "숙소", 30_000, Map.of(hostMemberId, 10_000L, secondMemberId, 10_000L, thirdMemberId, 10_000L));
        createExpense(second, "식사", 6_000, Map.of(hostMemberId, 2_000L, secondMemberId, 2_000L, thirdMemberId, 2_000L));

        // 미리보기: 계산 결과와 계좌 미등록 수취인을 보여주지만 DB에는 저장하지 않는다.
        mvc.perform(get(url + "/preview").header(AUTHORIZATION, bearer(second)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balances.length()").value(3))
            .andExpect(jsonPath("$.data.balances[0].memberId").value(hostMemberId))
            .andExpect(jsonPath("$.data.balances[0].paidAmount").value(30_000))
            .andExpect(jsonPath("$.data.balances[0].shareAmount").value(12_000))
            .andExpect(jsonPath("$.data.transfers.length()").value(2))
            .andExpect(jsonPath("$.data.transfers[0].senderId").value(secondMemberId))
            .andExpect(jsonPath("$.data.transfers[0].recipientId").value(hostMemberId))
            .andExpect(jsonPath("$.data.transfers[0].amount").value(6_000))
            .andExpect(jsonPath("$.data.transfers[1].senderId").value(thirdMemberId))
            .andExpect(jsonPath("$.data.transfers[1].amount").value(12_000))
            .andExpect(jsonPath("$.data.missingAccounts[0].memberId").value(hostMemberId))
            .andExpect(jsonPath("$.data.missingAccounts[0].nickname").value(host.getNickname()));
        assertEquals(0, count("SELECT COUNT(*) FROM settlements WHERE meeting_id = ?", meetingId));

        // 수취인(모임장) 계좌가 없으면 확정을 거절하고, 정산 행도 남기지 않는다.
        assertError(mvc.perform(post(url + "/confirm").header(AUTHORIZATION, bearer(host))), 409);
        assertEquals(0, count("SELECT COUNT(*) FROM settlements WHERE meeting_id = ?", meetingId));

        // 계좌 등록: 은행명·예금주의 앞뒤 공백을 제거해 저장한다.
        mvc.perform(put(url + "/account").header(AUTHORIZATION, bearer(host)).contentType(APPLICATION_JSON)
                .content("""
                    {"bankName":" 국민은행 ","accountNumber":"123-456","accountHolder":"\\t모임장 "}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bankName").value("국민은행"))
            .andExpect(jsonPath("$.data.accountHolder").value("모임장"));
        assertEquals(Map.of("bank_name", "국민은행", "account_number", "123-456", "account_holder", "모임장"),
            jdbc.queryForMap("SELECT bank_name, account_number, account_holder FROM settlement_accounts"
                + " WHERE meeting_id = ? AND member_id = ?", meetingId, hostMemberId));

        // 모임장이 아니면 확정할 수 없다.
        assertError(mvc.perform(post(url + "/confirm").header(AUTHORIZATION, bearer(second))), 403);

        // when: 모임장이 확정
        mvc.perform(post(url + "/confirm").header(AUTHORIZATION, bearer(host)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CLOSED"))
            .andExpect(jsonPath("$.data.closedByMemberId").value(hostMemberId))
            .andExpect(jsonPath("$.data.closedAt").isNotEmpty())
            .andExpect(jsonPath("$.data.transfers.length()").value(2))
            .andExpect(jsonPath("$.data.missingAccountMemberIds").isEmpty());

        // then: 확정 결과가 각 테이블에 순서대로 저장된다.
        Map<String, Object> settlement = jdbc.queryForMap(
            "SELECT id, status, closed_by_member_id, closed_at, version FROM settlements WHERE meeting_id = ?", meetingId);
        long settlementId = ((Number) settlement.get("id")).longValue();
        assertEquals("CLOSED", settlement.get("status"));
        assertEquals(hostMemberId, ((Number) settlement.get("closed_by_member_id")).longValue());
        assertEquals(0L, ((Number) settlement.get("version")).longValue());
        assertEquals(List.of(
                List.of(hostMemberId, 30_000L, 12_000L),
                List.of(secondMemberId, 6_000L, 12_000L),
                List.of(thirdMemberId, 0L, 12_000L)),
            jdbc.query("SELECT member_id, paid_amount, share_amount FROM settlement_balances"
                    + " WHERE settlement_id = ? ORDER BY balance_order", (rs, row) ->
                    List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)), settlementId));
        assertEquals(List.of(
                List.of(secondMemberId, hostMemberId, 6_000L),
                List.of(thirdMemberId, hostMemberId, 12_000L)),
            jdbc.query("SELECT sender_id, recipient_id, amount FROM settlement_transfers"
                    + " WHERE settlement_id = ? ORDER BY transfer_order", (rs, row) ->
                    List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)), settlementId));
        assertEquals(List.of("국민은행 123-456 모임장"),
            jdbc.query("SELECT CONCAT(bank_name, ' ', account_number, ' ', account_holder)"
                    + " FROM settlement_account_snapshots WHERE settlement_id = ? ORDER BY account_order",
                (rs, row) -> rs.getString(1), settlementId));
        // 정산 커밋 후 별도 트랜잭션으로 참여 중인 모임원 3명에게 알림을 저장한다.
        assertEquals(3, count("SELECT COUNT(*) FROM notifications WHERE meeting_id = ? AND type = 'SETTLEMENT_CLOSED'",
            meetingId));

        // 저장된 결과 조회: 보내는 사람에게는 받는 사람의 계좌를 보여준다.
        mvc.perform(get(url).header(AUTHORIZATION, bearer(third)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.settlementId").value(settlementId))
            .andExpect(jsonPath("$.data.balances.length()").value(3))
            .andExpect(jsonPath("$.data.accounts[0].memberId").value(hostMemberId))
            .andExpect(jsonPath("$.data.accounts[0].accountNumber").value("123-456"));
        // 받기만 하는 모임장에게는 보여줄 계좌가 없다.
        mvc.perform(get(url).header(AUTHORIZATION, bearer(host)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accounts").isEmpty());

        // 내 정산 내역: 회원3 본인과 관련된 금액·송금만 보여준다.
        mvc.perform(get("/api/settlements/mine").header(AUTHORIZATION, bearer(third)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].meetingName").value("정산 통합 테스트"))
            .andExpect(jsonPath("$.data[0].result.balances.length()").value(1))
            .andExpect(jsonPath("$.data[0].result.balances[0].memberId").value(thirdMemberId))
            .andExpect(jsonPath("$.data[0].result.transfers.length()").value(1))
            .andExpect(jsonPath("$.data[0].result.transfers[0].amount").value(12_000))
            .andExpect(jsonPath("$.data[0].names." + hostMemberId).value(host.getNickname()));

        // 확정 후에는 다시 확정하거나 지출을 추가할 수 없고, 저장된 결과도 바뀌지 않는다.
        assertError(mvc.perform(post(url + "/confirm").header(AUTHORIZATION, bearer(host))), 409);
        assertError(mvc.perform(post("/api/meetings/" + meetingId + "/expenses").header(AUTHORIZATION, bearer(host))
            .contentType(APPLICATION_JSON).content(expenseJson("추가", 1_000, Map.of(hostMemberId, 1_000L)))), 409);
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM settlements WHERE id = ?", Long.class, settlementId));
        assertEquals(2, count("SELECT COUNT(*) FROM settlement_transfers WHERE settlement_id = ?", settlementId));
    }

    @Test
    @DisplayName("확정된 정산이 없으면 결과 조회는 404, 미리보기는 OPEN 정산을 만들지 않는다")
    void resultBeforeConfirm() throws Exception {
        createExpense(host, "식사", 1_000, Map.of(hostMemberId, 1_000L));

        assertError(mvc.perform(get(url).header(AUTHORIZATION, bearer(host))), 404);
        mvc.perform(get(url + "/preview").header(AUTHORIZATION, bearer(host)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transfers").isEmpty());
        assertEquals(0, count("SELECT COUNT(*) FROM settlements WHERE meeting_id = ?", meetingId));
    }

    @Test
    @DisplayName("모임원이 아니거나 없는 모임이면 거절하고, 잘못된 계좌는 저장하지 않는다")
    void rejectsInvalidAccess() throws Exception {
        assertError(mvc.perform(get(url + "/preview").header(AUTHORIZATION, bearer(outsider))), 403);
        assertError(mvc.perform(get("/api/meetings/" + Long.MAX_VALUE + "/settlement/preview")
            .header(AUTHORIZATION, bearer(host))), 404);

        assertError(mvc.perform(put(url + "/account").header(AUTHORIZATION, bearer(host)).contentType(APPLICATION_JSON)
            .content("""
                {"bankName":"국민은행","accountNumber":"12-ab","accountHolder":"모임장"}
                """)), 400);
        assertEquals(0, count("SELECT COUNT(*) FROM settlement_accounts WHERE meeting_id = ?", meetingId));
    }

    private void createExpense(User payer, String title, long amount, Map<Long, Long> shares) throws Exception {
        mvc.perform(post("/api/meetings/" + meetingId + "/expenses").header(AUTHORIZATION, bearer(payer))
                .contentType(APPLICATION_JSON).content(expenseJson(title, amount, shares)))
            .andExpect(status().isCreated());
    }

    private String expenseJson(String title, long amount, Map<Long, Long> shares) {
        String participants = String.join(",", shares.entrySet().stream()
            .map(share -> "{\"memberId\":" + share.getKey() + ",\"amount\":" + share.getValue() + "}").toList());
        return "{\"title\":\"" + title + "\",\"amount\":" + amount
            + ",\"splitMode\":\"EXACT\",\"randomRemainder\":false,\"participants\":[" + participants + "]}";
    }

    private String bearer(User user) {
        return "Bearer " + tokens.createAccessToken(user.getId());
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void assertError(ResultActions result, int code) throws Exception {
        result.andExpect(status().is(code))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(code));
    }
}
