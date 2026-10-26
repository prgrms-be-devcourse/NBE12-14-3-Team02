package com.prgms.backend.domain.settlement;

import com.prgms.backend.domain.settlement.entity.Settlement;
import com.prgms.backend.domain.settlement.entity.SettlementAccount;
import com.prgms.backend.domain.settlement.entity.SettlementAccountSnapshot;
import com.prgms.backend.domain.settlement.entity.SettlementBalance;
import com.prgms.backend.domain.settlement.enums.SettlementStatus;
import com.prgms.backend.domain.settlement.entity.SettlementTransfer;
import com.prgms.backend.domain.settlement.repository.SettlementAccountRepository;
import com.prgms.backend.domain.settlement.repository.SettlementRepository;
import com.prgms.backend.support.MySqlContainerSupport;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SettlementPersistenceTest extends MySqlContainerSupport {
    // API 통합 테스트가 커밋한 실제 모임 ID와 겹치지 않는다.
    private static final long MEETING_ID = 780001;

    @Autowired private SettlementRepository settlements;
    @Autowired private SettlementAccountRepository accounts;
    @Autowired private EntityManager entityManager;

    @Test @DisplayName("version이 null인 정산은 신규 저장되고, 재조회 후 확정 시 버전과 컬렉션이 유지")
    void saveReloadAndUpdate() {
        Settlement settlement = new Settlement(MEETING_ID);
        assertEquals(0L, settlement.getId());
        assertNull(settlement.getVersion());

        long id = settlements.saveAndFlush(settlement).getId();
        assertNotEquals(0L, id);
        entityManager.clear();

        Settlement found = java.util.Objects.requireNonNull(settlements.findByMeetingId(MEETING_ID));
        assertEquals(id, found.getId());
        assertEquals(0L, found.getVersion());
        assertEquals(SettlementStatus.OPEN, found.getStatus());
        assertNull(found.getClosedByMemberId());
        assertNull(found.getClosedAt());
        // no-arg 생성자로 복원된 엔티티에서도 컬렉션이 null이 아니다
        assertTrue(found.getBalances().isEmpty());
        assertTrue(found.getTransfers().isEmpty());
        assertTrue(found.getAccounts().isEmpty());

        SettlementAccount recipient = accounts.saveAndFlush(new SettlementAccount(MEETING_ID, 1, "은행", "001-234", "예금주"));
        found.saveResult(
                List.of(new SettlementBalance(1, 300, 100), new SettlementBalance(2, 0, 200)),
                List.of(new SettlementTransfer(2, 1, 200)));
        found.saveAccounts(List.of(new SettlementAccountSnapshot(recipient)));
        found.close(1);
        entityManager.flush();
        entityManager.clear();

        Settlement closed = settlements.findById(id).orElseThrow();
        assertEquals(1L, closed.getVersion());
        assertEquals(SettlementStatus.CLOSED, closed.getStatus());
        assertEquals(1L, closed.getClosedByMemberId());
        assertNotNull(closed.getClosedAt());
        assertEquals(List.of(1L, 2L), closed.getBalances().stream().map(SettlementBalance::getMemberId).toList());
        assertEquals(200L, closed.getBalances().get(1).getShareAmount());
        assertEquals(2L, closed.getTransfers().getFirst().getSenderId());
        assertEquals(1L, closed.getTransfers().getFirst().getRecipientId());
        assertEquals("001-234", closed.getAccounts().getFirst().getAccountNumber());
        assertFalse(closed.canRegisterMissingAccount(1));

        // 외부에 노출한 목록을 바꿔도 엔티티 상태는 바뀌지 않는다
        assertThrows(UnsupportedOperationException.class, () -> closed.getAccounts().clear());
        assertEquals(1, closed.getAccounts().size());
    }

    @Test @DisplayName("기존 CLOSED 정산의 누락 계좌를 보충하면 버전만 증가하고 확정 결과와 기존 스냅샷은 유지")
    void registerMissingAccountAfterReload() {
        SettlementAccount existing = accounts.saveAndFlush(
            new SettlementAccount(MEETING_ID, 1, "기존 은행", "111", "기존 수취인"));
        Settlement settlement = new Settlement(MEETING_ID);
        settlement.saveResult(
            List.of(new SettlementBalance(1, 300, 100), new SettlementBalance(2, 300, 100),
                new SettlementBalance(3, 0, 400)),
            List.of(new SettlementTransfer(3, 1, 200), new SettlementTransfer(3, 2, 200)));
        settlement.saveAccounts(List.of(new SettlementAccountSnapshot(existing)));
        settlement.close(1);
        long id = settlements.saveAndFlush(settlement).getId();
        entityManager.clear();

        Settlement closed = settlements.findById(id).orElseThrow();
        var closedAt = closed.getClosedAt();
        long version = closed.getVersion();
        assertTrue(closed.canRegisterMissingAccount(2));
        SettlementAccount missing = accounts.saveAndFlush(
            new SettlementAccount(MEETING_ID, 2, "추가 은행", "222", "추가 수취인"));
        closed.registerMissingAccount(missing);
        entityManager.flush();
        entityManager.clear();

        Settlement reloaded = settlements.findById(id).orElseThrow();
        assertEquals(version + 1, reloaded.getVersion());
        assertEquals(SettlementStatus.CLOSED, reloaded.getStatus());
        assertEquals(1L, reloaded.getClosedByMemberId());
        assertEquals(closedAt, reloaded.getClosedAt());
        assertEquals(List.of(List.of(1L, 300L, 100L), List.of(2L, 300L, 100L), List.of(3L, 0L, 400L)),
            reloaded.getBalances().stream().map(b -> List.of(b.getMemberId(), b.getPaidAmount(), b.getShareAmount())).toList());
        assertEquals(List.of(List.of(3L, 1L, 200L), List.of(3L, 2L, 200L)),
            reloaded.getTransfers().stream().map(t -> List.of(t.getSenderId(), t.getRecipientId(), t.getAmount())).toList());
        assertEquals(List.of(1L, 2L), reloaded.getAccounts().stream().map(SettlementAccountSnapshot::getMemberId).toList());
        assertEquals(List.of("기존 은행/111/기존 수취인", "추가 은행/222/추가 수취인"),
            reloaded.getAccounts().stream()
                .map(a -> a.getBankName() + "/" + a.getAccountNumber() + "/" + a.getAccountHolder()).toList());
        assertFalse(reloaded.canRegisterMissingAccount(2));
        var error = assertThrows(com.prgms.backend.global.exception.BusinessException.class,
            () -> reloaded.registerMissingAccount(missing));
        assertEquals(409, error.getCode());
        entityManager.flush();
        entityManager.clear();
        Settlement unchanged = settlements.findById(id).orElseThrow();
        assertEquals(version + 1, unchanged.getVersion());
        assertEquals(2, unchanged.getAccounts().size());
    }

    @Test @DisplayName("계좌는 기존 strip 기준으로 공백을 제거하고, 수정 실패 시 기존 값을 유지")
    void accountSaveAndUpdate() {
        SettlementAccount account = new SettlementAccount(MEETING_ID, 1, " 은행\t", "001-234", " 예금주\n");
        assertEquals("은행", account.getBankName());
        // 기존 String.strip() 기준: NBSP는 공백으로 보지 않는다
        assertEquals(" 예금주", account.getAccountHolder());
        assertDoesNotThrow(() -> new SettlementAccount(10, 2, " ", "1", "예금주"));
        assertThrows(IllegalArgumentException.class, () -> new SettlementAccount(10, 2, " \t", "1", "예금주"));
        assertThrows(IllegalArgumentException.class, () -> new SettlementAccount(10, 2, null, "1", "예금주"));
        assertThrows(IllegalArgumentException.class, () -> new SettlementAccount(10, 2, "은행", "1-", "예금주"));
        assertThrows(IllegalArgumentException.class, () -> new SettlementAccount(0, 2, "은행", "1", "예금주"));

        long id = accounts.saveAndFlush(account).getId();
        entityManager.clear();

        SettlementAccount found = java.util.Objects.requireNonNull(accounts.findByMeetingIdAndMemberId(MEETING_ID, 1));
        assertEquals(id, found.getId());
        assertThrows(IllegalArgumentException.class, () -> found.update("새 은행", "abc", "새 예금주"));
        assertEquals("은행", found.getBankName());
        found.update(" 새 은행 ", "999", "새 예금주");
        entityManager.flush();
        entityManager.clear();

        SettlementAccount updated = accounts.findById(id).orElseThrow();
        assertEquals("새 은행", updated.getBankName());
        assertEquals("999", updated.getAccountNumber());
        assertEquals("새 예금주", updated.getAccountHolder());
    }
}
