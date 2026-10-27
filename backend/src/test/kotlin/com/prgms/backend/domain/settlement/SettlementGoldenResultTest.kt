package com.prgms.backend.domain.settlement

import com.prgms.backend.domain.expense.entity.Expense
import com.prgms.backend.domain.expense.entity.Expense.Companion.register
import com.prgms.backend.domain.settlement.dto.SettlementResponse
import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.entity.SettlementAccount
import com.prgms.backend.domain.settlement.entity.SettlementAccountSnapshot
import com.prgms.backend.domain.settlement.entity.SettlementBalance
import com.prgms.backend.domain.settlement.entity.SettlementTransfer
import com.prgms.backend.domain.settlement.service.SettlementCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.Random
import java.util.TreeSet
import kotlin.math.min
import kotlin.test.assertNotNull
import kotlin.test.fail

// Kotlin 전환 전후의 정산 결과가 같은지 비교하는 골든(기준값) 테스트.
// src/test/resources/settlement/golden-results.txt는 전환 전 Java 코드(1134c4d, 지출·정산 엔티티 전환 전)에서
// 이 테스트를 실행해 만든 결과이며, 현재 코드의 결과가 한 줄이라도 다르면 실패한다.
// 계산기 → 정산 엔티티(Settlement·SettlementAccount) → 응답 DTO까지 거친 결과를 비교하므로
// 이후 계산기·DTO를 Kotlin으로 옮길 때도 이 테스트가 그대로 통과해야 한다.
//
// 기준값 파일은 전환 전 Java 실행 결과를 유지하며 현재 Kotlin 코드의 결과와 비교한다.
//
// 계산 규칙을 의도적으로 바꿔 기준값을 다시 만들어야 할 때만 아래처럼 실행한다.
//   SETTLEMENT_GOLDEN_OUT=src/test/resources/settlement/golden-results.txt \
//     ./gradlew test --tests '*SettlementGoldenResultTest'
internal class SettlementGoldenResultTest {
    private val calculator = SettlementCalculator()

    @Test
    fun `고정된 정산 사례의 계산·저장·응답 결과가 전환 전 Java 결과와 같다`() {
        val actual = render()

        val out = System.getenv("SETTLEMENT_GOLDEN_OUT")
        if (out != null) {
            Files.write(Path.of(out), actual, StandardCharsets.UTF_8)
            return
        }

        val expected = readGolden()
        for (line in 0..<min(expected.size, actual.size)) {
            if (expected[line] != actual[line]) {
                fail(
                    (
                        "기준값과 " + (line + 1) + "번째 줄이 다릅니다. (" + caseOf(expected, line) + ")\n" +
                            "  기대: " + expected[line] + "\n" +
                            "  실제: " + actual[line]
                    ),
                )
            }
        }
        assertEquals(expected.size, actual.size, "기준값과 결과 줄 수가 다릅니다.")
    }

    private fun render(): List<String> {
        val lines = mutableListOf<String>()
        var number = 0
        for (scenario in handwrittenScenarios()) {
            render(lines, ++number, scenario)
        }
        for (scenario in invalidScenarios()) {
            render(lines, ++number, scenario)
        }
        val random = Random(20261023)
        for (index in 0..299) {
            render(lines, ++number, randomScenario(random, index))
        }
        renderAccountRules(lines)
        return lines
    }

    private fun render(
        lines: MutableList<String>,
        number: Int,
        scenario: Scenario,
    ) {
        lines.add("# " + number + " " + scenario.name)
        val result: SettlementCalculator.Result
        try {
            result = calculator.calculate(MEETING_ID, scenario.expenses(), scenario.memberIds)
        } catch (exception: RuntimeException) {
            lines.add("error=" + describe(exception))
            return
        }
        lines.add(
            "balances=" +
                result.balances
                    .map { b -> b.memberId.toString() + ":" + b.paidAmount + "/" + b.shareAmount + "/" + b.netAmount() }
                    .toList(),
        )
        lines.add(
            "transfers=" +
                result.transfers
                    .map { t -> t.senderId.toString() + "->" + t.recipientId + ":" + t.amount }
                    .toList(),
        )

        // 서비스의 확정 과정과 같은 순서로 결과를 엔티티에 저장한 뒤 응답 DTO로 다시 꺼낸다.
        val recipients: MutableSet<Long> = TreeSet()
        result.transfers.forEach { transfer -> recipients.add(transfer.recipientId) }
        val snapshots = mutableListOf<SettlementAccountSnapshot>()
        for (recipient in recipients) {
            // 일부 수취인은 계좌가 없는 경우를 만들어 미등록 수취인 목록도 비교한다.
            if (recipient % 4 != 3L) {
                snapshots.add(SettlementAccountSnapshot(account(recipient)))
            }
        }
        val settlement = Settlement(MEETING_ID)
        settlement.saveResult(
            result.balances
                .map { b ->
                    SettlementBalance(
                        b.memberId,
                        b.paidAmount,
                        b.shareAmount,
                    )
                }.toList(),
            result.transfers
                .map { t ->
                    SettlementTransfer(
                        t.senderId,
                        t.recipientId,
                        t.amount,
                    )
                }.toList(),
        )
        settlement.saveAccounts(snapshots)
        settlement.close(LEADER_ID)
        assertNotNull(settlement.closedAt)
        lines.add(
            (
                "entity=status:" + settlement.status.name + " meeting:" + settlement.meetingId +
                    " closedBy:" + settlement.closedByMemberId +
                    " balances:" +
                    settlement
                        .getBalances()
                        .map { b -> b.memberId.toString() + ":" + b.paidAmount + "/" + b.shareAmount }
                        .toList() +
                    " transfers:" +
                    settlement
                        .getTransfers()
                        .map { t -> t.senderId.toString() + "->" + t.recipientId + ":" + t.amount }
                        .toList() +
                    " accounts:" +
                    settlement
                        .getAccounts()
                        .map { a ->
                            (
                                a.memberId.toString() + ":" + escape(a.bankName) + "|" + a.accountNumber +
                                    "|" + escape(a.accountHolder)
                            )
                        }.toList()
            ),
        )
        for (memberId in scenario.memberIds.sorted().toList()) {
            lines.add(
                "view " + memberId + " all=" +
                    view(
                        SettlementResponse.forMember(
                            settlement,
                            memberId,
                            false,
                        ),
                    ),
            )
            lines.add(
                "view " + memberId + " mine=" +
                    view(
                        SettlementResponse.forMember(
                            settlement,
                            memberId,
                            true,
                        ),
                    ),
            )
        }
        for (memberId in recipients) {
            lines.add(
                "canRegisterMissing " + memberId + "=" +
                    settlement.canRegisterMissingAccount(
                        memberId,
                    ),
            )
        }
    }

    // settlementId·closedAt은 저장 여부·실행 시각에 따라 달라지므로 비교하지 않는다.
    private fun view(response: SettlementResponse): String =
        (
            "status:" + response.status.name + " meeting:" + response.meetingId +
                " closedBy:" + response.closedByMemberId +
                " balances:" +
                response.balances
                    .map { b -> b.memberId.toString() + ":" + b.paidAmount + "/" + b.shareAmount }
                    .toList() +
                " transfers:" +
                response.transfers
                    .map { t -> t.senderId.toString() + "->" + t.recipientId + ":" + t.amount }
                    .toList() +
                " accounts:" +
                response.accounts
                    .map { a ->
                        (
                            a.memberId.toString() + ":" + escape(a.bankName) + "|" + a.accountNumber +
                                "|" + escape(a.accountHolder)
                        )
                    }.toList() +
                " missing:" + response.missingAccountMemberIds
        )

    // 은행명·예금주는 공백 처리(strip) 결과까지 비교하도록 일반 공백·탭·NBSP가 섞인 값으로 만든다.
    private fun account(memberId: Long): SettlementAccount {
        val banks = arrayOf("국민은행", " 신한은행\t", " 우리은행", "카카오 뱅크\n")
        val holders = arrayOf("예금주" + memberId, "\t홍길동 ", "김 철수 ", " 이영희")
        return SettlementAccount(
            meetingId = MEETING_ID,
            memberId = memberId,
            bankName = banks[(memberId % banks.size).toInt()],
            accountNumber = "110-" + memberId + "-" + (memberId * 7 % 1000),
            accountHolder = holders[(memberId * 3 % holders.size).toInt()],
        )
    }

    private fun handwrittenScenarios(): List<Scenario> {
        val three = setOf(1L, 2L, 3L)
        return listOf(
            Scenario("한 명이 전액 결제", three) {
                listOf(
                    exact(1, 30000, mapOf(1L to 10000L, 2L to 10000L, 3L to 10000L)),
                )
            },
            Scenario("여러 결제자의 지정 부담액", three) {
                listOf(
                    exact(1, 30000, mapOf(1L to 5000L, 2L to 20000L, 3L to 5000L)),
                    exact(2, 12000, mapOf(1L to 4000L, 3L to 8000L)),
                )
            },
            Scenario("부담하지 않는 결제자", three) {
                listOf(
                    exact(1, 9000, mapOf(2L to 4500L, 3L to 4500L)),
                )
            },
            Scenario("각자 자기 몫만 결제", three) {
                listOf(
                    exact(1, 1000, mapOf(1L to 1000L)),
                    exact(2, 2000, mapOf(2L to 2000L)),
                )
            },
            Scenario("지출 없음", three) { mutableListOf() },
            Scenario("기존 지출의 균등 분배와 1원 차액", setOf(1L, 2L, 3L, 4L)) {
                listOf(
                    legacy(1, 10, setOf(1L, 2L, 3L)),
                    legacy(4, 1001, setOf(2L, 3L, 4L, 1L)),
                )
            },
            Scenario("부담액 0원 참여자", three) {
                listOf(
                    exact(3, 5000, mapOf(1L to 0L, 2L to 5000L, 3L to 0L)),
                )
            },
            Scenario("탈퇴 회원 포함·떨어진 ID", setOf(5L, 17L, 42L, 1000L)) {
                listOf(
                    exact(1000, 70001, mapOf(5L to 23333L, 17L to 23334L, 42L to 23334L)),
                    exact(17, 3, mapOf(1000L to 3L)),
                )
            },
            Scenario("한 수취인이 여러 명에게 받음", setOf(1L, 2L, 3L, 4L, 5L)) {
                listOf(
                    exact(
                        5,
                        100000,
                        mapOf(1L to 25000L, 2L to 25000L, 3L to 25000L, 4L to 25000L),
                    ),
                    exact(1, 10000, mapOf(5L to 10000L)),
                )
            },
            Scenario("큰 금액", setOf(1L, 2L)) {
                listOf(
                    exact(
                        1,
                        999999999999L,
                        mapOf(1L to 499999999999L, 2L to 500000000000L),
                    ),
                )
            },
        )
    }

    private fun invalidScenarios(): List<Scenario> {
        val three = setOf(1L, 2L, 3L)
        return listOf(
            Scenario("다른 모임의 지출", three) {
                listOf(
                    register(
                        99,
                        1,
                        "타 모임",
                        100,
                        null,
                        mapOf(2L to 100L),
                        "EXACT",
                        null,
                        null,
                    ),
                )
            },
            Scenario("모임원이 아닌 부담자", three) { listOf(exact(1, 100, mapOf(9L to 100L))) },
            Scenario("모임원이 아닌 결제자", three) { listOf(exact(9, 100, mapOf(1L to 100L))) },
            Scenario("음수 부담액", three) {
                listOf(
                    exact(
                        1,
                        100,
                        mapOf(1L to -1L, 2L to 101L),
                    ),
                )
            },
            Scenario("부담액 합계 불일치", three) { listOf(exact(1, 100, mapOf(1L to 99L))) },
            Scenario("0원 지출", three) { listOf(exact(1, 0, mapOf(1L to 0L))) },
            Scenario("같은 지출 객체 중복", three) {
                val expense = exact(1, 100, mapOf(2L to 100L))
                listOf(expense, expense)
            },
            Scenario("같은 DB ID 중복", three) {
                val first = exact(1, 100, mapOf(2L to 100L))
                val second = exact(2, 100, mapOf(1L to 100L))
                ReflectionTestUtils.setField(first, "id", 7L)
                ReflectionTestUtils.setField(second, "id", 7L)
                listOf(first, second)
            },
            Scenario("모임원 없음", setOf()) { listOf(exact(1, 100, mapOf(1L to 100L))) },
            Scenario("금액 합계 overflow", setOf(1L, 2L)) {
                listOf(
                    exact(1, Long.MAX_VALUE, mapOf(1L to Long.MAX_VALUE)),
                    exact(1, 1, mapOf(2L to 1L)),
                )
            },
        )
    }

    // 모임원 수·ID·지출 수·금액·분담 방식을 고정 시드로 섞어 만든다.
    private fun randomScenario(
        random: Random,
        index: Int,
    ): Scenario {
        val memberCount = 1 + random.nextInt(7)
        val memberIds: MutableSet<Long> = LinkedHashSet()
        while (memberIds.size < memberCount) {
            memberIds.add(1L + random.nextInt(40))
        }
        val memberList = memberIds.toList()
        val expenseCount = random.nextInt(9)
        val payers = mutableListOf<LongArray>()
        val shareList: MutableList<Map<Long, Long>> =
            ArrayList()
        val legacyList = mutableListOf<Boolean>()
        for (expense in 0..<expenseCount) {
            val payer: Long = memberList[random.nextInt(memberList.size)]
            val participants = mutableListOf<Long>()
            for (memberId in memberList) {
                if (random.nextInt(3) > 0) participants.add(memberId)
            }
            if (participants.isEmpty()) participants.add(memberList.first())
            val shares = mutableMapOf<Long, Long>()
            var amount: Long = 0
            for (participant in participants) {
                val share = (if (random.nextInt(5) == 0) 0 else 1 + random.nextInt(200000)).toLong()
                shares.put(participant, share)
                amount += share
            }
            if (amount == 0L) {
                shares.put(participants.first(), 1L)
                amount = 1
            }
            val legacy = random.nextInt(4) == 0
            payers.add(
                longArrayOf(
                    payer,
                    if (legacy) (1 + random.nextInt(500000)).toLong() else amount,
                ),
            )
            shareList.add(shares)
            legacyList.add(legacy)
        }
        // 지출 객체는 실행할 때마다 새로 만든다. (계산 중 객체가 바뀌어도 다음 비교에 영향이 없도록)
        return Scenario("random-" + index, memberIds) {
            val expenses = mutableListOf<Expense>()
            for (expense in payers.indices) {
                val payer = payers[expense][0]
                val amount = payers[expense][1]
                expenses.add(
                    if (legacyList[expense]) {
                        legacy(payer, amount, shareList[expense].keys)
                    } else {
                        exact(payer, amount, shareList[expense])
                    },
                )
            }
            expenses
        }
    }

    // 계좌 입력값의 검증과 공백 처리 결과를 비교한다.
    private fun renderAccountRules(lines: MutableList<String>) {
        lines.add("# account-rules")
        val inputs =
            listOf(
                listOf<String?>("국민은행", "123-456", "홍길동"),
                listOf<String?>(" 국민은행\t", "123", "\n홍길동 "),
                listOf<String?>(" ", "1", " "),
                listOf<String?>("　은행　", "1", "예금주"),
                listOf<String?>(" \t", "1", "예금주"),
                listOf<String?>("", "1", "예금주"),
                listOf<String?>(null, "1", "예금주"),
                listOf<String?>("은행", null, "예금주"),
                listOf<String?>("은행", "1", null),
                listOf<String?>("은행", "12--3", "예금주"),
                listOf<String?>("은행", "-12", "예금주"),
                listOf<String?>("은행", "12a", "예금주"),
                listOf<String?>("은행", "١٢٣", "예금주"),
                listOf<String?>("가".repeat(100), "1".repeat(50), "나".repeat(100)),
                listOf<String?>("가".repeat(101), "1", "예금주"),
                listOf<String?>(" " + "가".repeat(100), "1", "예금주"),
                listOf<String?>("은행", "1".repeat(51), "예금주"),
            )
        for (input in inputs) {
            val label: String =
                escape(input[0]) + "|" + escape(input[1]) + "|" + escape(input[2])
            lines.add(
                "create " + label + " => " +
                    attempt({
                        val account =
                            SettlementAccount(
                                meetingId = MEETING_ID,
                                memberId = 2,
                                bankName = input[0],
                                accountNumber = input[1],
                                accountHolder = input[2],
                            )
                        escape(account.bankName) + "|" + account.accountNumber + "|" + escape(account.accountHolder)
                    }),
            )
            // 수정에 실패하면 기존 값이 그대로 남는지도 함께 비교한다.
            val account =
                SettlementAccount(
                    meetingId = MEETING_ID,
                    memberId = 2,
                    bankName = "기존은행",
                    accountNumber = "000",
                    accountHolder = "기존예금주",
                )
            val outcome =
                attempt({
                    account.update(input[0], input[1], input[2])
                    "updated"
                })
            lines.add(
                (
                    "update " + label + " => " + outcome + " / now " + escape(account.bankName) + "|" +
                        account.accountNumber + "|" + escape(account.accountHolder)
                ),
            )
        }
        lines.add(
            "create invalid ids => " +
                attempt({
                    SettlementAccount(
                        meetingId = 0,
                        memberId = 2,
                        bankName = "은행",
                        accountNumber = "1",
                        accountHolder = "예금주",
                    ).bankName
                }),
        )
        lines.add("settlement invalid meeting => " + attempt({ Settlement(0).status.name }))
        lines.add(
            "settlement invalid leader => " +
                attempt({
                    Settlement(MEETING_ID).close(0)
                    "closed"
                }),
        )
        lines.add(
            "settlement close twice => " +
                attempt({
                    val settlement = Settlement(MEETING_ID)
                    settlement.close(LEADER_ID)
                    settlement.close(LEADER_ID)
                    "closed"
                }),
        )
    }

    private fun attempt(action: () -> String): String {
        try {
            return "ok " + action()
        } catch (exception: RuntimeException) {
            return describe(exception)
        }
    }

    private fun describe(exception: RuntimeException): String =
        "error " + exception.javaClass.simpleName + ": " + exception.message

    private fun exact(
        payer: Long,
        amount: Long,
        shares: Map<Long, Long>,
    ): Expense = Expense.register(MEETING_ID, payer, "식사", amount, null, shares, "EXACT", null, null)

    // 개인별 부담액 저장 전에 등록된 기존 지출 (splitMode 없음 → 계산기가 균등 분배)
    private fun legacy(
        payer: Long,
        amount: Long,
        participants: Set<Long>,
    ): Expense =
        Expense(
            meetingId = MEETING_ID,
            payerMemberId = payer,
            title = "기존",
            amount = amount,
            memo = null,
            participantIds = participants,
        )

    private fun readGolden(): List<String> {
        javaClass.getResourceAsStream(GOLDEN).use { input ->
            assertNotNull(input, GOLDEN + " 기준값 파일이 없습니다.")
            return input.bufferedReader(StandardCharsets.UTF_8).readLines()
        }
    }

    private fun caseOf(
        lines: List<String>,
        line: Int,
    ): String {
        for (index in line downTo 0) {
            if (lines[index].startsWith("#")) return lines[index]
        }
        return ""
    }

    private data class Scenario(
        val name: String,
        val memberIds: Set<Long>,
        val expenses: () -> List<Expense>,
    )

    companion object {
        private const val GOLDEN = "/settlement/golden-results.txt"
        private const val MEETING_ID: Long = 10
        private const val LEADER_ID: Long = 1

        // 눈에 보이지 않는 공백 문자를 \\uXXXX로 바꿔 결과 파일에서 차이를 확인할 수 있게 한다.
        private fun escape(value: String?): String {
            if (value == null) return "null"
            val builder = StringBuilder()
            for (character in value.toCharArray()) {
                if (character != ' ' && (
                        Character.isWhitespace(character) ||
                            Character.isSpaceChar(
                                character,
                            )
                    )
                ) {
                    builder.append(String.format("\\u%04x", character.code))
                } else {
                    builder.append(character)
                }
            }
            return builder.toString()
        }
    }
}
