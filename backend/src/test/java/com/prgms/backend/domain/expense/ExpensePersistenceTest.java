package com.prgms.backend.domain.expense;

import com.prgms.backend.domain.expense.entity.Expense;
import com.prgms.backend.domain.expense.entity.ExpenseRegistration;
import com.prgms.backend.domain.expense.repository.ExpenseRegistrationRepository;
import com.prgms.backend.domain.expense.repository.ExpenseRepository;
import com.prgms.backend.support.MySqlContainerSupport;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExpensePersistenceTest extends MySqlContainerSupport {
    private static final long MEETING_ID = 780002;

    @Autowired private ExpenseRepository expenses;
    @Autowired private ExpenseRegistrationRepository registrations;
    @Autowired private EntityManager entityManager;

    @Test @DisplayName("ID가 0인 지출은 신규 저장되고, 재조회 시 컬렉션·등록 시각·분담 정보가 유지")
    void saveAndReload() {
        Expense expense = Expense.register(MEETING_ID, 1, "  식사\t", 300, "메모",
                Map.of(1L, 100L, 2L, 200L), "EXACT", 100, 2L);
        assertEquals(0L, expense.getId());
        // 기존 String.strip() 기준: 일반 공백·탭은 제거하고 NBSP는 유지
        assertEquals("\u00a0 식사\t".strip(), expense.getTitle());
        assertEquals("\u00a0 식사", expense.getTitle());

        long id = expenses.saveAndFlush(expense).getId();
        assertNotEquals(0L, id);
        entityManager.clear();

        Expense found = expenses.findById(id).orElseThrow();
        assertNotSame(expense, found);
        assertEquals(MEETING_ID, found.getMeetingId());
        assertEquals(1L, found.getPayerMemberId());
        assertEquals(300L, found.getAmount());
        assertEquals("메모", found.getMemo());
        assertNotNull(found.getCreatedAt());
        assertEquals(Set.of(1L, 2L), found.getParticipantIds());
        assertEquals(Map.of(1L, 100L, 2L, 200L), found.getParticipantAmounts());
        assertEquals("EXACT", found.getSplitMode());
        assertEquals(100, found.getRoundingUnit());
        assertEquals(2L, found.getRemainderMemberId());
        assertNull(found.getReceiptKey());

        // no-arg 생성자로 복원된 엔티티에서도 도메인 메서드가 컬렉션을 교체
        found.update(" 수정\n", 500, null, Map.of(3L, 500L), "EXACT", null, null);
        found.attachReceipt("receipt/key");
        entityManager.flush();
        entityManager.clear();

        Expense updated = expenses.findById(id).orElseThrow();
        assertEquals(id, updated.getId());
        assertEquals(1L, updated.getPayerMemberId());
        assertEquals("수정", updated.getTitle());
        assertEquals(500L, updated.getAmount());
        assertEquals(updated.getAmount(), updated.getParticipantAmounts().values().stream().mapToLong(Long::longValue).sum());
        assertNull(updated.getMemo());
        assertEquals("EXACT", updated.getSplitMode());
        assertNull(updated.getRoundingUnit());
        assertNull(updated.getRemainderMemberId());
        assertEquals(Set.of(3L), updated.getParticipantIds());
        assertEquals(Map.of(3L, 500L), updated.getParticipantAmounts());
        assertEquals("receipt/key", updated.getReceiptKey());
        assertEquals(found.getCreatedAt(), updated.getCreatedAt());
        assertTrue(expenses.existsMemberInExpense(MEETING_ID, 3L));
        assertFalse(expenses.existsMemberInExpense(MEETING_ID, 2L));
    }

    @Test @DisplayName("요청 번호는 저장 후 재조회해도 같은 지출을 가리킨다")
    void registrationSaveAndReload() {
        String key = UUID.randomUUID().toString();
        long id = registrations.saveAndFlush(new ExpenseRegistration(MEETING_ID, 1, key, "f".repeat(64), 7)).getId();
        assertNotEquals(0L, id);
        entityManager.clear();

        ExpenseRegistration found = registrations.findByMeetingIdAndMemberIdAndRequestKey(MEETING_ID, 1, key).orElseThrow();
        assertEquals(id, found.getId());
        assertEquals(7L, found.getExpenseId());
        assertEquals("f".repeat(64), found.getFingerprint());
    }
}
