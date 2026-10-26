package com.prgms.backend.domain.settlement;

import com.prgms.backend.domain.expense.entity.Expense;
import com.prgms.backend.domain.expense.entity.ExpenseRegistration;
import com.prgms.backend.domain.expense.repository.ExpenseRegistrationRepository;
import com.prgms.backend.domain.expense.repository.ExpenseRepository;
import com.prgms.backend.domain.settlement.entity.*;
import com.prgms.backend.domain.settlement.repository.SettlementAccountRepository;
import com.prgms.backend.domain.settlement.repository.SettlementRepository;
import com.prgms.backend.domain.settlement.service.SettlementCalculator;
import com.prgms.backend.support.MySqlContainerSupport;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;

// 일반 테스트 소스 밖에 둔다. 생성은 README의 절차대로 Java 기준 커밋에서만 실행한다.
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JavaSettlementFixtureGeneratorTest extends MySqlContainerSupport {
    @Autowired private ExpenseRepository expenses;
    @Autowired private ExpenseRegistrationRepository registrations;
    @Autowired private SettlementRepository settlements;
    @Autowired private SettlementAccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @Test
    void exportJavaPersistedRows() throws Exception {
        String output = System.getenv("SETTLEMENT_FIXTURE_OUT");
        assertNotNull(output, "SETTLEMENT_FIXTURE_OUT에 출력 경로를 지정하세요.");
        // 공유 테스트 DB의 일반 테스트 데이터와 겹치지 않는 ID로 고정한다.
        for (String table : List.of("expenses", "settlements", "settlement_accounts", "expense_registrations")) {
            jdbc.execute("ALTER TABLE " + table + " AUTO_INCREMENT = 900000");
        }
        Expense legacy = expenses.saveAndFlush(new Expense(890001, 41, "기존 균등 지출", 10, null, Set.of(41L, 5L, 17L)));
        Expense exact = expenses.saveAndFlush(Expense.register(890001, 17, "큰 지정 지출", 3_000_000_001L,
                "Java에서 저장", Map.of(5L, 0L, 17L, 1L, 41L, 3_000_000_000L), "EXACT", null, null));
        Expense equal = expenses.saveAndFlush(Expense.register(890001, 41, "단위 분담", 10_000, null,
                Map.of(5L, 3300L, 17L, 3300L, 41L, 3400L), "EQUAL", 100, 41L));
        exact.attachReceipt("java/receipt");
        registrations.saveAndFlush(new ExpenseRegistration(890001, 17,
                "12345678-1234-1234-1234-123456789abc", "a".repeat(64), exact.getId()));
        SettlementAccount account = accounts.saveAndFlush(new SettlementAccount(890001, 17,
                " 은행\t", "001-234", "\u00a0예금주\n"));
        var result = new SettlementCalculator().calculate(890001, List.of(legacy, exact, equal), Set.of(5L, 17L, 41L));
        Settlement closed = new Settlement(890001);
        closed.saveResult(result.balances().stream()
                        .map(b -> new SettlementBalance(b.memberId(), b.paidAmount(), b.shareAmount())).toList(),
                result.transfers().stream().map(t -> new SettlementTransfer(t.senderId(), t.recipientId(), t.amount())).toList());
        closed.saveAccounts(List.of(new SettlementAccountSnapshot(account)));
        closed.close(41);
        settlements.saveAndFlush(closed);
        settlements.saveAndFlush(new Settlement(890002));
        entityManager.flush();
        // 실행 시각만 고정한다. 금액·컬렉션·옵션·버전은 Java JPA가 기록한 그대로 내보낸다.
        jdbc.update("UPDATE expenses SET created_at = '2026-10-01 12:34:56.123456' WHERE meeting_id = 890001");
        jdbc.update("UPDATE settlements SET closed_at = '2026-10-02 13:45:57.654321' WHERE meeting_id = 890001");
        StringBuilder sql = new StringBuilder("-- Generated with Java entities at commit 1134c4d.\n"
                + "-- Generator: src/test/fixtures/settlement/JavaSettlementFixtureGeneratorTest.java\n"
                + "-- Only timestamps are normalized. Do not regenerate with Kotlin entities.\n");
        Map<String, String> orders = Map.of("expense_participants", "expense_id, member_id", "expense_shares", "expense_id, member_id",
                "settlement_balances", "settlement_id, balance_order", "settlement_transfers", "settlement_id, transfer_order",
                "settlement_account_snapshots", "settlement_id, account_order");
        for (String table : List.of("expenses", "expense_participants", "expense_shares", "expense_registrations",
                "settlement_accounts", "settlements", "settlement_balances", "settlement_transfers", "settlement_account_snapshots")) {
            jdbc.query("SELECT * FROM " + table + " ORDER BY " + orders.getOrDefault(table, "id"), rs -> {
                var metadata = rs.getMetaData();
                StringBuilder columns = new StringBuilder();
                StringBuilder values = new StringBuilder();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    if (index > 1) { columns.append(", "); values.append(", "); }
                    columns.append('`').append(metadata.getColumnName(index)).append('`');
                    Object value = rs.getObject(index);
                    values.append(value == null ? "NULL" : value instanceof Number ? value.toString()
                            : "'" + value.toString().replace("\\", "\\\\").replace("'", "''") + "'");
                }
                sql.append("INSERT INTO ").append(table).append(" (").append(columns).append(") VALUES (")
                        .append(values).append(");\n");
            });
        }
        Files.writeString(Path.of(output), sql, StandardCharsets.UTF_8);
    }
}
