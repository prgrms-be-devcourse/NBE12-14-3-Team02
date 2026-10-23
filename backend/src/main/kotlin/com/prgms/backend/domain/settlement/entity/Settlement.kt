package com.prgms.backend.domain.settlement.entity

import com.prgms.backend.domain.settlement.enums.SettlementStatus
import com.prgms.backend.global.exception.custom.settlement.SettlementAlreadyDoneException
import com.prgms.backend.global.exception.custom.settlement.SettlementRequestException
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.LocalDateTime

@Entity
@Table(name = "settlements", uniqueConstraints = [UniqueConstraint(columnNames = ["meeting_id"])])
class Settlement(
    meetingId: Long,
) {
    init {
        require(meetingId > 0) { "모임 ID가 필요합니다." }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @Column(name = "meeting_id", nullable = false)
    var meetingId: Long = meetingId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: SettlementStatus = SettlementStatus.OPEN
        protected set

    var closedByMemberId: Long? = null
        protected set

    var closedAt: LocalDateTime? = null
        protected set

    // Spring Data JPA가 null 여부로 신규 엔티티를 판별하므로 0으로 초기화하지 않습니다.
    @Version
    var version: Long? = null
        protected set

    @ElementCollection
    @CollectionTable(name = "settlement_balances", joinColumns = [JoinColumn(name = "settlement_id")])
    @OrderColumn(name = "balance_order")
    private var balances: MutableList<SettlementBalance> = mutableListOf()

    @ElementCollection
    @CollectionTable(name = "settlement_transfers", joinColumns = [JoinColumn(name = "settlement_id")])
    @OrderColumn(name = "transfer_order")
    private var transfers: MutableList<SettlementTransfer> = mutableListOf()

    @ElementCollection
    @CollectionTable(name = "settlement_account_snapshots", joinColumns = [JoinColumn(name = "settlement_id")])
    @OrderColumn(name = "account_order")
    private var accounts: MutableList<SettlementAccountSnapshot> = mutableListOf()

    fun getBalances(): List<SettlementBalance> = balances.toList()

    fun getTransfers(): List<SettlementTransfer> = transfers.toList()

    fun getAccounts(): List<SettlementAccountSnapshot> = accounts.toList()

    fun checkOpen() {
        if (status == SettlementStatus.CLOSED) {
            throw SettlementAlreadyDoneException()
        }
    }

    fun saveAccounts(accounts: List<SettlementAccountSnapshot>) {
        checkOpen()
        this.accounts.clear()
        this.accounts.addAll(accounts)
    }

    fun canRegisterMissingAccount(memberId: Long): Boolean =
        status == SettlementStatus.CLOSED &&
            transfers.any { it.recipientId == memberId } &&
            accounts.none { it.memberId == memberId }

    // 확정된 금액과 기존 계좌는 유지하고, 미등록 수취인의 계좌만 최초 1회 보충합니다.
    fun registerMissingAccount(account: SettlementAccount) {
        if (!canRegisterMissingAccount(account.memberId)) {
            throw SettlementRequestException(409, "확정 후에는 미등록 수취인의 계좌만 등록할 수 있습니다.")
        }
        accounts.add(SettlementAccountSnapshot(account))
    }

    fun saveResult(
        balances: List<SettlementBalance>,
        transfers: List<SettlementTransfer>,
    ) {
        checkOpen()
        this.balances.clear()
        this.balances.addAll(balances)
        this.transfers.clear()
        this.transfers.addAll(transfers)
    }

    // 모임장 확정 (권한 확인) 후 계산 결과 저장
    fun close(leaderMemberId: Long) {
        require(leaderMemberId > 0) { "모임장 ID가 필요합니다." }
        checkOpen()
        status = SettlementStatus.CLOSED
        closedByMemberId = leaderMemberId
        closedAt = LocalDateTime.now()
    }
}
