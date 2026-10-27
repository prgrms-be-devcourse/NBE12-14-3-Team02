package com.prgms.backend.domain.settlement

import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.entity.SettlementAccount
import com.prgms.backend.domain.settlement.entity.SettlementAccountSnapshot
import com.prgms.backend.domain.settlement.entity.SettlementBalance
import com.prgms.backend.domain.settlement.entity.SettlementTransfer
import com.prgms.backend.domain.settlement.enums.SettlementStatus
import com.prgms.backend.domain.settlement.repository.SettlementAccountRepository
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.global.exception.BusinessException
import com.prgms.backend.support.MySqlContainerSupport
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import kotlin.test.assertNotNull

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
internal class SettlementPersistenceTest : MySqlContainerSupport() {
    @Autowired
    private lateinit var settlements: SettlementRepository

    @Autowired
    private lateinit var accounts: SettlementAccountRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `version이 null인 정산은 신규 저장되고, 재조회 후 확정 시 버전과 컬렉션이 유지`() {
        val settlement = Settlement(MEETING_ID)
        assertEquals(0L, settlement.id)
        assertNull(settlement.version)

        val id = settlements.saveAndFlush(settlement).id
        assertNotEquals(0L, id)
        entityManager.clear()

        val found = assertNotNull(settlements.findByMeetingId(MEETING_ID))
        assertEquals(id, found.id)
        assertEquals(0L, found.version)
        assertEquals(SettlementStatus.OPEN, found.status)
        assertNull(found.closedByMemberId)
        assertNull(found.closedAt)
        // no-arg 생성자로 복원된 엔티티에서도 컬렉션이 null이 아니다
        assertTrue(found.getBalances().isEmpty())
        assertTrue(found.getTransfers().isEmpty())
        assertTrue(found.getAccounts().isEmpty())

        val recipient =
            accounts.saveAndFlush(
                SettlementAccount(
                    meetingId = MEETING_ID,
                    memberId = 1,
                    bankName = "은행",
                    accountNumber = "001-234",
                    accountHolder = "예금주",
                ),
            )
        found.saveResult(
            listOf(
                SettlementBalance(1, 300, 100),
                SettlementBalance(2, 0, 200),
            ),
            listOf(SettlementTransfer(2, 1, 200)),
        )
        found.saveAccounts(listOf(SettlementAccountSnapshot(recipient)))
        found.close(1)
        entityManager.flush()
        entityManager.clear()

        val closed = settlements.findById(id).orElseThrow()
        assertEquals(1L, closed.version)
        assertEquals(SettlementStatus.CLOSED, closed.status)
        assertEquals(1L, closed.closedByMemberId)
        assertNotNull(closed.closedAt)
        assertEquals(
            mutableListOf(1L, 2L),
            closed.getBalances().map(SettlementBalance::memberId).toList(),
        )
        assertEquals(200L, closed.getBalances().get(1).shareAmount)
        assertEquals(2L, closed.getTransfers().first().senderId)
        assertEquals(1L, closed.getTransfers().first().recipientId)
        assertEquals("001-234", closed.getAccounts().first().accountNumber)
        assertFalse(closed.canRegisterMissingAccount(1))

        // 외부에 노출한 목록을 바꿔도 엔티티 상태는 바뀌지 않는다
        assertThrows<UnsupportedOperationException> {
            (closed.getAccounts() as MutableList<SettlementAccountSnapshot>)
                .clear()
        }
        assertEquals(1, closed.getAccounts().size)
    }

    @Test
    fun `기존 CLOSED 정산의 누락 계좌를 보충하면 버전만 증가하고 확정 결과와 기존 스냅샷은 유지`() {
        val existing =
            accounts.saveAndFlush(
                SettlementAccount(
                    meetingId = MEETING_ID,
                    memberId = 1,
                    bankName = "기존 은행",
                    accountNumber = "111",
                    accountHolder = "기존 수취인",
                ),
            )
        val settlement = Settlement(MEETING_ID)
        settlement.saveResult(
            listOf(
                SettlementBalance(1, 300, 100),
                SettlementBalance(2, 300, 100),
                SettlementBalance(3, 0, 400),
            ),
            listOf(
                SettlementTransfer(3, 1, 200),
                SettlementTransfer(3, 2, 200),
            ),
        )
        settlement.saveAccounts(
            listOf(
                SettlementAccountSnapshot(
                    existing,
                ),
            ),
        )
        settlement.close(1)
        val id = settlements.saveAndFlush(settlement).id
        entityManager.clear()

        val closed = settlements.findById(id).orElseThrow()
        val closedAt = closed.closedAt
        val version: Long = assertNotNull(closed.version)
        assertTrue(closed.canRegisterMissingAccount(2))
        val missing =
            accounts.saveAndFlush(
                SettlementAccount(
                    meetingId = MEETING_ID,
                    memberId = 2,
                    bankName = "추가 은행",
                    accountNumber = "222",
                    accountHolder = "추가 수취인",
                ),
            )
        closed.registerMissingAccount(missing)
        entityManager.flush()
        entityManager.clear()

        val reloaded = settlements.findById(id).orElseThrow()
        assertEquals(version + 1, reloaded.version)
        assertEquals(SettlementStatus.CLOSED, reloaded.status)
        assertEquals(1L, reloaded.closedByMemberId)
        assertEquals(closedAt, reloaded.closedAt)
        assertEquals(
            listOf(
                mutableListOf(
                    1L,
                    300L,
                    100L,
                ),
                mutableListOf(2L, 300L, 100L),
                mutableListOf(3L, 0L, 400L),
            ),
            reloaded
                .getBalances()
                .map { b ->
                    listOf(
                        b.memberId,
                        b.paidAmount,
                        b.shareAmount,
                    )
                }.toList(),
        )
        assertEquals(
            listOf(
                mutableListOf(
                    3L,
                    1L,
                    200L,
                ),
                mutableListOf(3L, 2L, 200L),
            ),
            reloaded
                .getTransfers()
                .map { t ->
                    listOf(
                        t.senderId,
                        t.recipientId,
                        t.amount,
                    )
                }.toList(),
        )
        assertEquals(
            mutableListOf(1L, 2L),
            reloaded.getAccounts().map(SettlementAccountSnapshot::memberId).toList(),
        )
        assertEquals(
            mutableListOf("기존 은행/111/기존 수취인", "추가 은행/222/추가 수취인"),
            reloaded
                .getAccounts()
                .map { a -> a.bankName + "/" + a.accountNumber + "/" + a.accountHolder }
                .toList(),
        )
        assertFalse(reloaded.canRegisterMissingAccount(2))
        val error = assertThrows<BusinessException> { reloaded.registerMissingAccount(missing) }
        assertEquals(409, error.code)
        entityManager.flush()
        entityManager.clear()
        val unchanged = settlements.findById(id).orElseThrow()
        assertEquals(version + 1, unchanged.version)
        assertEquals(2, unchanged.getAccounts().size)
    }

    @Test
    fun `계좌는 기존 strip 기준으로 공백을 제거하고, 수정 실패 시 기존 값을 유지`() {
        val account =
            SettlementAccount(
                meetingId = MEETING_ID,
                memberId = 1,
                bankName = " 은행\t",
                accountNumber = "001-234",
                accountHolder = " 예금주\n",
            )
        assertEquals("은행", account.bankName)
        // 기존 String.strip() 기준: NBSP는 공백으로 보지 않는다
        assertEquals(" 예금주", account.accountHolder)
        assertDoesNotThrow {
            SettlementAccount(
                meetingId = 10,
                memberId = 2,
                bankName = " ",
                accountNumber = "1",
                accountHolder = "예금주",
            )
        }
        assertThrows<IllegalArgumentException> {
            SettlementAccount(
                meetingId = 10,
                memberId = 2,
                bankName = " \t",
                accountNumber = "1",
                accountHolder = "예금주",
            )
        }
        assertThrows<IllegalArgumentException> {
            SettlementAccount(
                meetingId = 10,
                memberId = 2,
                bankName = null,
                accountNumber = "1",
                accountHolder = "예금주",
            )
        }
        assertThrows<IllegalArgumentException> {
            SettlementAccount(
                meetingId = 10,
                memberId = 2,
                bankName = "은행",
                accountNumber = "1-",
                accountHolder = "예금주",
            )
        }
        assertThrows<IllegalArgumentException> {
            SettlementAccount(
                meetingId = 0,
                memberId = 2,
                bankName = "은행",
                accountNumber = "1",
                accountHolder = "예금주",
            )
        }

        val id = accounts.saveAndFlush(account).id
        entityManager.clear()

        val found =
            assertNotNull(
                accounts.findByMeetingIdAndMemberId(
                    MEETING_ID,
                    1,
                ),
            )
        assertEquals(id, found.id)
        assertThrows<IllegalArgumentException> { found.update("새 은행", "abc", "새 예금주") }
        assertEquals("은행", found.bankName)
        found.update(" 새 은행 ", "999", "새 예금주")
        entityManager.flush()
        entityManager.clear()

        val updated = accounts.findById(id).orElseThrow()
        assertEquals("새 은행", updated.bankName)
        assertEquals("999", updated.accountNumber)
        assertEquals("새 예금주", updated.accountHolder)
    }

    companion object {
        // API 통합 테스트가 커밋한 실제 모임 ID와 겹치지 않는다.
        private const val MEETING_ID: Long = 780001
    }
}
