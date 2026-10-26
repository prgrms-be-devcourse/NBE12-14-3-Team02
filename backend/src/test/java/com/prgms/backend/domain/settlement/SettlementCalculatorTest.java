package com.prgms.backend.domain.settlement;

import com.prgms.backend.domain.expense.entity.Expense;
import com.prgms.backend.domain.settlement.service.SettlementCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementCalculatorTest {
    private final SettlementCalculator calculator = new SettlementCalculator();
    private final Set<Long> members = Set.of(1L, 2L, 3L);

    @Test @DisplayName("한 명이 전액 결제: 지출 3만원, 실제 송금은 2만원")
    void onePayer() {
        var expenses = List.of(expense(1, 30_000, Map.of(1L, 10_000L, 2L, 10_000L, 3L, 10_000L)));
        var result = calculator.calculate(10, expenses, members);
        assertEquals(List.of(new SettlementCalculator.Transfer(2, 1, 10_000),
                new SettlementCalculator.Transfer(3, 1, 10_000)), result.getTransfers());
        assertConservation(expenses, result);
    }

    @Test @DisplayName("여러 결제자의 지정 부담액을 다시 균등 분배하지 않는다")
    void multiplePayers() {
        var expenses = List.of(expense(1, 30_000, Map.of(1L, 20_000L, 2L, 10_000L)),
                expense(2, 12_000, Map.of(1L, 4_000L, 2L, 4_000L, 3L, 4_000L)));
        var result = calculator.calculate(10, expenses, members);
        assertEquals(List.of(new SettlementCalculator.Balance(1, 30_000, 24_000),
                new SettlementCalculator.Balance(2, 12_000, 14_000),
                new SettlementCalculator.Balance(3, 0, 4_000)), result.getBalances());
        assertEquals(List.of(new SettlementCalculator.Transfer(2, 1, 2_000),
                new SettlementCalculator.Transfer(3, 1, 4_000)), result.getTransfers());
        assertConservation(expenses, result);
    }

    @Test @DisplayName("부담자가 아닌 결제자는 대신 낸 금액을 전부 돌려받는다")
    void payerOutsideParticipants() {
        var expenses = List.of(expense(1, 101, Map.of(2L, 50L, 3L, 51L)));
        var result = calculator.calculate(10, expenses, members);
        assertEquals(List.of(new SettlementCalculator.Transfer(2, 1, 50),
                new SettlementCalculator.Transfer(3, 1, 51)), result.getTransfers());
        assertConservation(expenses, result);
    }

    @Test @DisplayName("각자 부담액만큼 결제하면 송금이 없다")
    void balanced() {
        var expenses = List.of(expense(1, 100, Map.of(1L, 50L, 2L, 50L)),
                expense(2, 100, Map.of(1L, 50L, 2L, 50L)));
        var result = calculator.calculate(10, expenses, members);
        assertTrue(result.getTransfers().isEmpty());
        assertConservation(expenses, result);
    }

    @Test @DisplayName("지출이 없으면 모든 금액이 0원이다")
    void empty() {
        var result = calculator.calculate(10, List.of(), members);
        assertEquals(3, result.getBalances().size());
        assertTrue(result.getTransfers().isEmpty());
        assertConservation(List.of(), result);
    }

    @Test @DisplayName("기존 지출의 1원 차액을 ID 순서로 배분한다")
    void legacyRemainder() {
        var expenses = List.of(new Expense(10, 1, "기존", 10, null, members));
        var result = calculator.calculate(10, expenses, members);
        assertEquals(List.of(new SettlementCalculator.Balance(1, 10, 4),
                new SettlementCalculator.Balance(2, 0, 3), new SettlementCalculator.Balance(3, 0, 3)), result.getBalances());
        assertConservation(expenses, result);
    }

    @Test @DisplayName("같은 객체 또는 같은 DB ID의 중복 지출을 거절한다")
    void duplicates() {
        Expense first = expense(1, 100, Map.of(2L, 100L));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(10, List.of(first, first), members));
        Expense second = expense(1, 100, Map.of(2L, 100L));
        ReflectionTestUtils.setField(first, "id", 7L);
        ReflectionTestUtils.setField(second, "id", 7L);
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(10, List.of(first, second), members));
    }

    @Test @DisplayName("다른 모임·잘못된 참여자·음수 부담액·합계 불일치를 거절한다")
    void invalid() {
        var invalid = List.of(
                Expense.register(99, 1, "타 모임", 100, null, Map.of(2L, 100L), "EXACT", null, null),
                expense(1, 100, Map.of(9L, 100L)), expense(9, 100, Map.of(1L, 100L)),
                expense(1, 100, Map.of(1L, -1L, 2L, 101L)), expense(1, 100, Map.of(1L, 99L)));
        for (Expense expense : invalid) {
            assertThrows(IllegalArgumentException.class, () -> calculator.calculate(10, List.of(expense), members));
        }
    }

    @Test @DisplayName("고정 시드의 200개 사례에서 전체·개인별 금액이 보존된다")
    void generatedCases() {
        Random random = new Random(20260923);
        for (int scenario = 0; scenario < 200; scenario++) {
            List<Expense> expenses = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                Map<Long, Long> shares = new HashMap<>();
                for (long id : members.stream().sorted().toList()) {
                    if (random.nextBoolean()) shares.put(id, (long) random.nextInt(100_000));
                }
                if (shares.values().stream().mapToLong(Long::longValue).sum() == 0) shares.put(1L, 1L);
                long total = shares.values().stream().mapToLong(Long::longValue).sum();
                long payer = 1 + random.nextInt(3);
                expenses.add(random.nextInt(4) == 0
                        ? new Expense(10, payer, "기존", total, null, shares.keySet())
                        : expense(payer, total, shares));
            }
            assertConservation(expenses, calculator.calculate(10, expenses, members));
        }
    }

    private Expense expense(long payer, long amount, Map<Long, Long> shares) {
        return Expense.register(10, payer, "식사", amount, null, shares, "EXACT", null, null);
    }

    // 기대 결제액·부담액은 계산기 결과가 아닌 원본 지출에서 독립적으로 집계한다.
    private void assertConservation(List<Expense> expenses, SettlementCalculator.Result result) {
        Map<Long, Long> expectedPaid = new HashMap<>();
        Map<Long, Long> expectedShares = new HashMap<>();
        for (long member : members) {
            expectedPaid.put(member, 0L);
            expectedShares.put(member, 0L);
        }
        for (Expense expense : expenses) {
            expectedPaid.merge(expense.getPayerMemberId(), expense.getAmount(), Math::addExact);
            if (expense.getSplitMode() == null && expense.getParticipantAmounts().isEmpty()) {
                // 기존 지출은 ID 오름차순으로 1원씩 남은 차액을 부담한다.
                var participants = expense.getParticipantIds().stream().sorted().toList();
                for (int index = 0; index < participants.size(); index++) {
                    long share = expense.getAmount() / participants.size()
                            + (index < expense.getAmount() % participants.size() ? 1 : 0);
                    expectedShares.merge(participants.get(index), share, Math::addExact);
                }
            } else {
                expense.getParticipantAmounts().forEach((member, share) -> expectedShares.merge(member, share, Math::addExact));
            }
        }
        assertEquals(members.size(), result.getBalances().size(), "회원별 잔액의 누락 또는 중복");
        assertEquals(members, result.getBalances().stream().map(SettlementCalculator.Balance::getMemberId)
                .collect(java.util.stream.Collectors.toSet()), "잔액의 회원 집합");
        long total = expenses.stream().mapToLong(Expense::getAmount).sum();
        assertEquals(total, result.getBalances().stream().mapToLong(SettlementCalculator.Balance::getPaidAmount).sum());
        assertEquals(total, result.getBalances().stream().mapToLong(SettlementCalculator.Balance::getShareAmount).sum());
        long sent = result.getTransfers().stream().mapToLong(SettlementCalculator.Transfer::getAmount).sum();
        assertEquals(sent, result.getBalances().stream().mapToLong(b -> Math.max(0, b.netAmount())).sum());
        assertEquals(sent, result.getBalances().stream().mapToLong(b -> Math.max(0, -b.netAmount())).sum());
        for (var transfer : result.getTransfers()) {
            assertTrue(transfer.getAmount() > 0);
            assertNotEquals(transfer.getSenderId(), transfer.getRecipientId());
            assertTrue(members.contains(transfer.getSenderId()) && members.contains(transfer.getRecipientId()));
        }
        for (var balance : result.getBalances()) {
            assertEquals(expectedPaid.get(balance.getMemberId()).longValue(), balance.getPaidAmount(),
                    "모임원 " + balance.getMemberId() + "의 원본 결제액");
            assertEquals(expectedShares.get(balance.getMemberId()).longValue(), balance.getShareAmount(),
                    "모임원 " + balance.getMemberId() + "의 원본 부담액");
            long outgoing = result.getTransfers().stream().filter(t -> t.getSenderId() == balance.getMemberId())
                    .mapToLong(SettlementCalculator.Transfer::getAmount).sum();
            long incoming = result.getTransfers().stream().filter(t -> t.getRecipientId() == balance.getMemberId())
                    .mapToLong(SettlementCalculator.Transfer::getAmount).sum();
            assertEquals(balance.getShareAmount(), balance.getPaidAmount() + outgoing - incoming,
                    "모임원 " + balance.getMemberId() + "의 최종 부담액");
        }
    }
}
