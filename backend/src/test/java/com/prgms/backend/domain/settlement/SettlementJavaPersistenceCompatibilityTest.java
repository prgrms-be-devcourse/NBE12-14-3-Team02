package com.prgms.backend.domain.settlement;

import com.prgms.backend.domain.expense.entity.Expense;
import com.prgms.backend.domain.expense.repository.ExpenseRegistrationRepository;
import com.prgms.backend.domain.expense.repository.ExpenseRepository;
import com.prgms.backend.domain.settlement.dto.SettlementResponse;
import com.prgms.backend.domain.settlement.entity.Settlement;
import com.prgms.backend.domain.settlement.repository.SettlementAccountRepository;
import com.prgms.backend.domain.settlement.repository.SettlementRepository;
import com.prgms.backend.domain.settlement.service.SettlementCalculator;
import com.prgms.backend.support.MySqlContainerSupport;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.*;

// fixture는 전환 전 Java 커밋 1134c4d가 실제 MySQL에 저장한 행이다.
// 현재 엔티티로 준비 데이터를 저장하지 않아 양쪽 매핑이 함께 틀리는 회귀도 잡는다.
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql("/settlement/java-persisted-fixture.sql")
class SettlementJavaPersistenceCompatibilityTest extends MySqlContainerSupport {
    private static final long MEETING_ID = 890001;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 1, 12, 34, 56, 123456000);
    private static final List<SettlementCalculator.Balance> BALANCES = List.of(
            new SettlementCalculator.Balance(5, 0, 3304),
            new SettlementCalculator.Balance(17, 3_000_000_001L, 3304),
            new SettlementCalculator.Balance(41, 10_010, 3_000_003_403L));
    private static final List<SettlementCalculator.Transfer> TRANSFERS = List.of(
            new SettlementCalculator.Transfer(5, 17, 3304),
            new SettlementCalculator.Transfer(41, 17, 2_999_993_393L));

    @Autowired private ExpenseRepository expenses;
    @Autowired private ExpenseRegistrationRepository registrations;
    @Autowired private SettlementRepository settlements;
    @Autowired private SettlementAccountRepository accounts;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("Java가 저장한 기존·지정·균등 지출을 읽어도 큰 금액·null 옵션·차액과 정산 결과가 같다")
    void readsJavaExpenses() {
        entityManager.clear();
        List<Expense> found = expenses.findByMeetingIdOrderByIdAsc(MEETING_ID);
        assertEquals(3, found.size());
        Expense legacy = found.get(0);
        assertEquals(900000L, legacy.getId());
        assertEquals(41L, legacy.getPayerMemberId());
        assertEquals("기존 균등 지출", legacy.getTitle());
        assertEquals(10L, legacy.getAmount());
        assertEquals(CREATED_AT, legacy.getCreatedAt());
        assertNull(legacy.getMemo());
        assertNull(legacy.getSplitMode());
        assertNull(legacy.getRoundingUnit());
        assertNull(legacy.getRemainderMemberId());
        assertEquals(Set.of(5L, 17L, 41L), legacy.getParticipantIds());
        assertTrue(legacy.getParticipantAmounts().isEmpty());

        Expense exact = found.get(1);
        assertEquals(900001L, exact.getId());
        assertEquals(17L, exact.getPayerMemberId());
        assertEquals(3_000_000_001L, exact.getAmount());
        assertEquals("EXACT", exact.getSplitMode());
        assertEquals("Java에서 저장", exact.getMemo());
        assertEquals("java/receipt", exact.getReceiptKey());
        assertEquals(CREATED_AT, exact.getCreatedAt());
        assertNull(exact.getRoundingUnit());
        assertNull(exact.getRemainderMemberId());
        assertEquals(Set.of(5L, 17L, 41L), exact.getParticipantIds());
        assertEquals(Map.of(5L, 0L, 17L, 1L, 41L, 3_000_000_000L), exact.getParticipantAmounts());

        Expense equal = found.get(2);
        assertEquals(10_000L, equal.getAmount());
        assertEquals("EQUAL", equal.getSplitMode());
        assertEquals(100, equal.getRoundingUnit());
        assertEquals(41L, equal.getRemainderMemberId());
        assertEquals(Map.of(5L, 3300L, 17L, 3300L, 41L, 3400L), equal.getParticipantAmounts());
        var registration = registrations.findByMeetingIdAndMemberIdAndRequestKey(MEETING_ID, 17,
                "12345678-1234-1234-1234-123456789abc").orElseThrow();
        assertEquals(exact.getId(), registration.getExpenseId());
        assertEquals("a".repeat(64), registration.getFingerprint());

        var result = new SettlementCalculator().calculate(MEETING_ID, found, Set.of(5L, 17L, 41L));
        assertEquals(BALANCES, result.balances());
        assertEquals(TRANSFERS, result.transfers());
    }

    @Test
    @DisplayName("Java가 확정한 정산의 금액·목록 순서·계좌 스냅샷·상태·시각·버전과 회원별 응답이 유지된다")
    void readsJavaSettlements() {
        entityManager.clear();
        Settlement closed = settlements.findByMeetingId(MEETING_ID).orElseThrow();
        assertEquals(900000L, closed.getId());
        assertEquals("CLOSED", closed.getStatus().name());
        assertEquals(41L, closed.getClosedByMemberId());
        assertEquals(LocalDateTime.of(2026, 10, 2, 13, 45, 57, 654321000), closed.getClosedAt());
        assertEquals(0L, closed.getVersion());
        assertEquals(BALANCES, closed.getBalances().stream()
                .map(b -> new SettlementCalculator.Balance(b.getMemberId(), b.getPaidAmount(), b.getShareAmount())).toList());
        assertEquals(TRANSFERS, closed.getTransfers().stream()
                .map(t -> new SettlementCalculator.Transfer(t.getSenderId(), t.getRecipientId(), t.getAmount())).toList());
        assertEquals(1, closed.getAccounts().size());
        var snapshot = closed.getAccounts().getFirst();
        assertEquals(17L, snapshot.getMemberId());
        assertEquals("은행", snapshot.getBankName());
        assertEquals("001-234", snapshot.getAccountNumber());
        assertEquals("\u00a0예금주", snapshot.getAccountHolder());
        var response = SettlementResponse.forMember(closed, 41, false);
        assertEquals(BALANCES, response.getBalances());
        assertEquals(TRANSFERS, response.getTransfers());
        assertEquals("001-234", response.getAccounts().getFirst().getAccountNumber());
        assertTrue(response.getMissingAccountMemberIds().isEmpty());
        var mine = SettlementResponse.forMember(closed, 41, true);
        assertEquals(List.of(BALANCES.get(2)), mine.getBalances());
        assertEquals(List.of(TRANSFERS.get(1)), mine.getTransfers());

        Settlement open = settlements.findByMeetingId(890002).orElseThrow();
        assertEquals("OPEN", open.getStatus().name());
        assertEquals(0L, open.getVersion());
        assertNull(open.getClosedAt());
        assertNull(open.getClosedByMemberId());
        assertTrue(open.getBalances().isEmpty());
        assertTrue(open.getTransfers().isEmpty());
        assertTrue(open.getAccounts().isEmpty());

        // 현재 계좌가 바뀌어도 Java가 확정한 스냅샷은 독립적으로 유지되어야 한다.
        accounts.findByMeetingIdAndMemberId(MEETING_ID, 17).orElseThrow().update("새은행", "999", "새예금주");
        entityManager.flush();
        entityManager.clear();
        Settlement reloaded = settlements.findByMeetingId(MEETING_ID).orElseThrow();
        assertEquals("001-234", reloaded.getAccounts().getFirst().getAccountNumber());
        assertEquals(0L, reloaded.getVersion());
    }

    @Test
    @DisplayName("Java가 저장한 기존 지출을 Kotlin으로 수정해 재조회해도 금액·분담액·등록 시각이 유지된다")
    void updatesJavaExpense() {
        Expense legacy = expenses.findById(900000L).orElseThrow();
        legacy.update("전환 후 수정", 13, "수정 메모", Map.of(5L, 13L), "EXACT", null, null);
        entityManager.flush();
        entityManager.clear();
        Expense updated = expenses.findById(900000L).orElseThrow();
        assertEquals(13L, updated.getAmount());
        assertEquals("전환 후 수정", updated.getTitle());
        assertEquals("수정 메모", updated.getMemo());
        assertEquals("EXACT", updated.getSplitMode());
        assertEquals(Set.of(5L), updated.getParticipantIds());
        assertEquals(Map.of(5L, 13L), updated.getParticipantAmounts());
        assertEquals(41L, updated.getPayerMemberId());
        assertEquals(CREATED_AT, updated.getCreatedAt());
        assertNull(updated.getRoundingUnit());
        assertNull(updated.getRemainderMemberId());
    }
}
