package com.prgms.backend.domain.settlement;

import com.prgms.backend.domain.expense.entity.Expense;
import com.prgms.backend.domain.settlement.dto.SettlementResponse;
import com.prgms.backend.domain.settlement.entity.Settlement;
import com.prgms.backend.domain.settlement.entity.SettlementAccount;
import com.prgms.backend.domain.settlement.entity.SettlementAccountSnapshot;
import com.prgms.backend.domain.settlement.entity.SettlementBalance;
import com.prgms.backend.domain.settlement.entity.SettlementTransfer;
import com.prgms.backend.domain.settlement.service.SettlementCalculator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

// Kotlin 전환 전후의 정산 결과가 같은지 비교하는 골든(기준값) 테스트.
// src/test/resources/settlement/golden-results.txt는 전환 전 Java 코드(1134c4d, 지출·정산 엔티티 전환 전)에서
// 이 테스트를 실행해 만든 결과이며, 현재 코드의 결과가 한 줄이라도 다르면 실패한다.
// 계산기 → 정산 엔티티(Settlement·SettlementAccount) → 응답 DTO까지 거친 결과를 비교하므로
// 이후 계산기·DTO를 Kotlin으로 옮길 때도 이 테스트가 그대로 통과해야 한다.
//
// 이 파일은 전환 전 커밋에서도 컴파일되도록 두 버전에 공통인 API만 사용한다.
// (예: SettlementStatus는 패키지가 바뀌어 import하지 않고, 저장 전 ID처럼 null/0으로 달라지는 값은 출력하지 않는다.)
//
// 계산 규칙을 의도적으로 바꿔 기준값을 다시 만들어야 할 때만 아래처럼 실행한다.
//   SETTLEMENT_GOLDEN_OUT=src/test/resources/settlement/golden-results.txt \
//     ./gradlew test --tests '*SettlementGoldenResultTest'
class SettlementGoldenResultTest {

    private static final String GOLDEN = "/settlement/golden-results.txt";
    private static final long MEETING_ID = 10;
    private static final long LEADER_ID = 1;

    private final SettlementCalculator calculator = new SettlementCalculator();

    @Test
    @DisplayName("고정된 정산 사례의 계산·저장·응답 결과가 전환 전 Java 결과와 같다")
    void matchesGoldenResults() throws IOException {
        List<String> actual = render();

        String out = System.getenv("SETTLEMENT_GOLDEN_OUT");
        if (out != null) {
            Files.write(Path.of(out), actual, StandardCharsets.UTF_8);
            return;
        }

        List<String> expected = readGolden();
        for (int line = 0; line < Math.min(expected.size(), actual.size()); line++) {
            if (!expected.get(line).equals(actual.get(line))) {
                fail("기준값과 " + (line + 1) + "번째 줄이 다릅니다. (" + caseOf(expected, line) + ")\n"
                    + "  기대: " + expected.get(line) + "\n"
                    + "  실제: " + actual.get(line));
            }
        }
        assertEquals(expected.size(), actual.size(), "기준값과 결과 줄 수가 다릅니다.");
    }

    private List<String> render() {
        List<String> lines = new ArrayList<>();
        int number = 0;
        for (Scenario scenario : handwrittenScenarios()) {
            render(lines, ++number, scenario);
        }
        for (Scenario scenario : invalidScenarios()) {
            render(lines, ++number, scenario);
        }
        Random random = new Random(20261023);
        for (int index = 0; index < 300; index++) {
            render(lines, ++number, randomScenario(random, index));
        }
        renderAccountRules(lines);
        return lines;
    }

    private void render(List<String> lines, int number, Scenario scenario) {
        lines.add("# " + number + " " + scenario.name());
        SettlementCalculator.Result result;
        try {
            result = calculator.calculate(MEETING_ID, scenario.expenses().get(), scenario.memberIds());
        } catch (RuntimeException exception) {
            lines.add("error=" + describe(exception));
            return;
        }
        lines.add("balances=" + result.balances().stream()
            .map(b -> b.memberId() + ":" + b.paidAmount() + "/" + b.shareAmount() + "/" + b.netAmount()).toList());
        lines.add("transfers=" + result.transfers().stream()
            .map(t -> t.senderId() + "->" + t.recipientId() + ":" + t.amount()).toList());

        // 서비스의 확정 과정과 같은 순서로 결과를 엔티티에 저장한 뒤 응답 DTO로 다시 꺼낸다.
        Set<Long> recipients = new TreeSet<>();
        result.transfers().forEach(transfer -> recipients.add(transfer.recipientId()));
        List<SettlementAccountSnapshot> snapshots = new ArrayList<>();
        for (long recipient : recipients) {
            // 일부 수취인은 계좌가 없는 경우를 만들어 미등록 수취인 목록도 비교한다.
            if (recipient % 4 != 3) {
                snapshots.add(new SettlementAccountSnapshot(account(recipient)));
            }
        }
        Settlement settlement = new Settlement(MEETING_ID);
        settlement.saveResult(
            result.balances().stream().map(b -> new SettlementBalance(b.memberId(), b.paidAmount(), b.shareAmount())).toList(),
            result.transfers().stream().map(t -> new SettlementTransfer(t.senderId(), t.recipientId(), t.amount())).toList());
        settlement.saveAccounts(snapshots);
        settlement.close(LEADER_ID);
        assertNotNull(settlement.getClosedAt());
        lines.add("entity=status:" + settlement.getStatus().name() + " meeting:" + settlement.getMeetingId()
            + " closedBy:" + settlement.getClosedByMemberId()
            + " balances:" + settlement.getBalances().stream()
                .map(b -> b.getMemberId() + ":" + b.getPaidAmount() + "/" + b.getShareAmount()).toList()
            + " transfers:" + settlement.getTransfers().stream()
                .map(t -> t.getSenderId() + "->" + t.getRecipientId() + ":" + t.getAmount()).toList()
            + " accounts:" + settlement.getAccounts().stream()
                .map(a -> a.getMemberId() + ":" + escape(a.getBankName()) + "|" + a.getAccountNumber()
                    + "|" + escape(a.getAccountHolder())).toList());
        for (long memberId : scenario.memberIds().stream().sorted().toList()) {
            lines.add("view " + memberId + " all=" + view(SettlementResponse.forMember(settlement, memberId, false)));
            lines.add("view " + memberId + " mine=" + view(SettlementResponse.forMember(settlement, memberId, true)));
        }
        for (long memberId : recipients) {
            lines.add("canRegisterMissing " + memberId + "=" + settlement.canRegisterMissingAccount(memberId));
        }
    }

    // settlementId·closedAt은 저장 여부·실행 시각에 따라 달라지므로 비교하지 않는다.
    private String view(SettlementResponse response) {
        return "status:" + response.getStatus().name() + " meeting:" + response.getMeetingId()
            + " closedBy:" + response.getClosedByMemberId()
            + " balances:" + response.getBalances().stream()
                .map(b -> b.memberId() + ":" + b.paidAmount() + "/" + b.shareAmount()).toList()
            + " transfers:" + response.getTransfers().stream()
                .map(t -> t.senderId() + "->" + t.recipientId() + ":" + t.amount()).toList()
            + " accounts:" + response.getAccounts().stream()
                .map(a -> a.getMemberId() + ":" + escape(a.getBankName()) + "|" + a.getAccountNumber()
                    + "|" + escape(a.getAccountHolder())).toList()
            + " missing:" + response.getMissingAccountMemberIds();
    }

    // 은행명·예금주는 공백 처리(strip) 결과까지 비교하도록 일반 공백·탭·NBSP가 섞인 값으로 만든다.
    private SettlementAccount account(long memberId) {
        String[] banks = {"국민은행", " 신한은행\t", " 우리은행", "카카오 뱅크\n"};
        String[] holders = {"예금주" + memberId, "\t홍길동 ", "김 철수 ", " 이영희"};
        return new SettlementAccount(MEETING_ID, memberId, banks[(int) (memberId % banks.length)],
            "110-" + memberId + "-" + (memberId * 7 % 1000), holders[(int) (memberId * 3 % holders.length)]);
    }

    private List<Scenario> handwrittenScenarios() {
        Set<Long> three = Set.of(1L, 2L, 3L);
        return List.of(
            new Scenario("한 명이 전액 결제", three, () -> List.of(
                exact(1, 30_000, Map.of(1L, 10_000L, 2L, 10_000L, 3L, 10_000L)))),
            new Scenario("여러 결제자의 지정 부담액", three, () -> List.of(
                exact(1, 30_000, Map.of(1L, 5_000L, 2L, 20_000L, 3L, 5_000L)),
                exact(2, 12_000, Map.of(1L, 4_000L, 3L, 8_000L)))),
            new Scenario("부담하지 않는 결제자", three, () -> List.of(
                exact(1, 9_000, Map.of(2L, 4_500L, 3L, 4_500L)))),
            new Scenario("각자 자기 몫만 결제", three, () -> List.of(
                exact(1, 1_000, Map.of(1L, 1_000L)), exact(2, 2_000, Map.of(2L, 2_000L)))),
            new Scenario("지출 없음", three, List::of),
            new Scenario("기존 지출의 균등 분배와 1원 차액", Set.of(1L, 2L, 3L, 4L), () -> List.of(
                legacy(1, 10, Set.of(1L, 2L, 3L)), legacy(4, 1_001, Set.of(2L, 3L, 4L, 1L)))),
            new Scenario("부담액 0원 참여자", three, () -> List.of(
                exact(3, 5_000, Map.of(1L, 0L, 2L, 5_000L, 3L, 0L)))),
            new Scenario("탈퇴 회원 포함·떨어진 ID", Set.of(5L, 17L, 42L, 1000L), () -> List.of(
                exact(1000, 70_001, Map.of(5L, 23_333L, 17L, 23_334L, 42L, 23_334L)),
                exact(17, 3, Map.of(1000L, 3L)))),
            new Scenario("한 수취인이 여러 명에게 받음", Set.of(1L, 2L, 3L, 4L, 5L), () -> List.of(
                exact(5, 100_000, Map.of(1L, 25_000L, 2L, 25_000L, 3L, 25_000L, 4L, 25_000L)),
                exact(1, 10_000, Map.of(5L, 10_000L)))),
            new Scenario("큰 금액", Set.of(1L, 2L), () -> List.of(
                exact(1, 999_999_999_999L, Map.of(1L, 499_999_999_999L, 2L, 500_000_000_000L)))));
    }

    private List<Scenario> invalidScenarios() {
        Set<Long> three = Set.of(1L, 2L, 3L);
        return List.of(
            new Scenario("다른 모임의 지출", three, () -> List.of(
                Expense.register(99, 1, "타 모임", 100, null, Map.of(2L, 100L), "EXACT", null, null))),
            new Scenario("모임원이 아닌 부담자", three, () -> List.of(exact(1, 100, Map.of(9L, 100L)))),
            new Scenario("모임원이 아닌 결제자", three, () -> List.of(exact(9, 100, Map.of(1L, 100L)))),
            new Scenario("음수 부담액", three, () -> List.of(exact(1, 100, Map.of(1L, -1L, 2L, 101L)))),
            new Scenario("부담액 합계 불일치", three, () -> List.of(exact(1, 100, Map.of(1L, 99L)))),
            new Scenario("0원 지출", three, () -> List.of(exact(1, 0, Map.of(1L, 0L)))),
            new Scenario("같은 지출 객체 중복", three, () -> {
                Expense expense = exact(1, 100, Map.of(2L, 100L));
                return List.of(expense, expense);
            }),
            new Scenario("같은 DB ID 중복", three, () -> {
                Expense first = exact(1, 100, Map.of(2L, 100L));
                Expense second = exact(2, 100, Map.of(1L, 100L));
                ReflectionTestUtils.setField(first, "id", 7L);
                ReflectionTestUtils.setField(second, "id", 7L);
                return List.of(first, second);
            }),
            new Scenario("모임원 없음", Set.of(), () -> List.of(exact(1, 100, Map.of(1L, 100L)))),
            new Scenario("금액 합계 overflow", Set.of(1L, 2L), () -> List.of(
                exact(1, Long.MAX_VALUE, Map.of(1L, Long.MAX_VALUE)), exact(1, 1, Map.of(2L, 1L)))));
    }

    // 모임원 수·ID·지출 수·금액·분담 방식을 고정 시드로 섞어 만든다.
    private Scenario randomScenario(Random random, int index) {
        int memberCount = 1 + random.nextInt(7);
        Set<Long> memberIds = new LinkedHashSet<>();
        while (memberIds.size() < memberCount) {
            memberIds.add(1L + random.nextInt(40));
        }
        List<Long> memberList = new ArrayList<>(memberIds);
        int expenseCount = random.nextInt(9);
        List<long[]> payers = new ArrayList<>();
        List<Map<Long, Long>> shareList = new ArrayList<>();
        List<Boolean> legacyList = new ArrayList<>();
        for (int expense = 0; expense < expenseCount; expense++) {
            long payer = memberList.get(random.nextInt(memberList.size()));
            List<Long> participants = new ArrayList<>();
            for (long memberId : memberList) {
                if (random.nextInt(3) > 0) participants.add(memberId);
            }
            if (participants.isEmpty()) participants.add(memberList.getFirst());
            Map<Long, Long> shares = new HashMap<>();
            long amount = 0;
            for (long participant : participants) {
                long share = random.nextInt(5) == 0 ? 0 : 1 + random.nextInt(200_000);
                shares.put(participant, share);
                amount += share;
            }
            if (amount == 0) {
                shares.put(participants.getFirst(), 1L);
                amount = 1;
            }
            boolean legacy = random.nextInt(4) == 0;
            payers.add(new long[] {payer, legacy ? 1 + random.nextInt(500_000) : amount});
            shareList.add(shares);
            legacyList.add(legacy);
        }
        // 지출 객체는 실행할 때마다 새로 만든다. (계산 중 객체가 바뀌어도 다음 비교에 영향이 없도록)
        return new Scenario("random-" + index, memberIds, () -> {
            List<Expense> expenses = new ArrayList<>();
            for (int expense = 0; expense < payers.size(); expense++) {
                long payer = payers.get(expense)[0];
                long amount = payers.get(expense)[1];
                expenses.add(legacyList.get(expense)
                    ? legacy(payer, amount, shareList.get(expense).keySet())
                    : exact(payer, amount, shareList.get(expense)));
            }
            return expenses;
        });
    }

    // 계좌 입력값의 검증과 공백 처리 결과를 비교한다.
    private void renderAccountRules(List<String> lines) {
        lines.add("# account-rules");
        String[][] inputs = {
            {"국민은행", "123-456", "홍길동"},
            {" 국민은행\t", "123", "\n홍길동 "},
            {" ", "1", " "},
            {"　은행　", "1", "예금주"},
            {" \t", "1", "예금주"},
            {"", "1", "예금주"},
            {null, "1", "예금주"},
            {"은행", null, "예금주"},
            {"은행", "1", null},
            {"은행", "12--3", "예금주"},
            {"은행", "-12", "예금주"},
            {"은행", "12a", "예금주"},
            {"은행", "١٢٣", "예금주"},
            {"가".repeat(100), "1".repeat(50), "나".repeat(100)},
            {"가".repeat(101), "1", "예금주"},
            {" " + "가".repeat(100), "1", "예금주"},
            {"은행", "1".repeat(51), "예금주"},
        };
        for (String[] input : inputs) {
            String label = escape(input[0]) + "|" + escape(input[1]) + "|" + escape(input[2]);
            lines.add("create " + label + " => " + attempt(() -> {
                SettlementAccount account = new SettlementAccount(MEETING_ID, 2, input[0], input[1], input[2]);
                return escape(account.getBankName()) + "|" + account.getAccountNumber() + "|" + escape(account.getAccountHolder());
            }));
            // 수정에 실패하면 기존 값이 그대로 남는지도 함께 비교한다.
            SettlementAccount account = new SettlementAccount(MEETING_ID, 2, "기존은행", "000", "기존예금주");
            String outcome = attempt(() -> {
                account.update(input[0], input[1], input[2]);
                return "updated";
            });
            lines.add("update " + label + " => " + outcome + " / now " + escape(account.getBankName()) + "|"
                + account.getAccountNumber() + "|" + escape(account.getAccountHolder()));
        }
        lines.add("create invalid ids => " + attempt(() -> new SettlementAccount(0, 2, "은행", "1", "예금주").getBankName()));
        lines.add("settlement invalid meeting => " + attempt(() -> new Settlement(0).getStatus().name()));
        lines.add("settlement invalid leader => " + attempt(() -> {
            new Settlement(MEETING_ID).close(0);
            return "closed";
        }));
        lines.add("settlement close twice => " + attempt(() -> {
            Settlement settlement = new Settlement(MEETING_ID);
            settlement.close(LEADER_ID);
            settlement.close(LEADER_ID);
            return "closed";
        }));
    }

    private String attempt(Supplier<String> action) {
        try {
            return "ok " + action.get();
        } catch (RuntimeException exception) {
            return describe(exception);
        }
    }

    private String describe(RuntimeException exception) {
        return "error " + exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }

    // 눈에 보이지 않는 공백 문자를 \\uXXXX로 바꿔 결과 파일에서 차이를 확인할 수 있게 한다.
    private static String escape(String value) {
        if (value == null) return "null";
        StringBuilder builder = new StringBuilder();
        for (char character : value.toCharArray()) {
            if (character != ' ' && (Character.isWhitespace(character) || Character.isSpaceChar(character))) {
                builder.append(String.format("\\u%04x", (int) character));
            } else {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    private Expense exact(long payer, long amount, Map<Long, Long> shares) {
        return Expense.register(MEETING_ID, payer, "식사", amount, null, shares, "EXACT", null, null);
    }

    // 개인별 부담액 저장 전에 등록된 기존 지출 (splitMode 없음 → 계산기가 균등 분배)
    private Expense legacy(long payer, long amount, Set<Long> participants) {
        return new Expense(MEETING_ID, payer, "기존", amount, null, participants);
    }

    private List<String> readGolden() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(GOLDEN)) {
            assertNotNull(input, GOLDEN + " 기준값 파일이 없습니다.");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    private String caseOf(List<String> lines, int line) {
        for (int index = line; index >= 0; index--) {
            if (lines.get(index).startsWith("#")) return lines.get(index);
        }
        return "";
    }

    private record Scenario(String name, Set<Long> memberIds, Supplier<List<Expense>> expenses) {}
}
