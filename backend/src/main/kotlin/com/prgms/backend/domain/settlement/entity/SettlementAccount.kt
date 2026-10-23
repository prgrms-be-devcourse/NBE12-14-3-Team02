package com.prgms.backend.domain.settlement.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

// Java 호출부가 null을 넘겨도 기존처럼 IllegalArgumentException으로 거절하도록 계좌 정보는 nullable로 받습니다.
@Entity
@Table(
    name = "settlement_accounts",
    uniqueConstraints = [UniqueConstraint(columnNames = ["meeting_id", "member_id"])],
)
class SettlementAccount(
    meetingId: Long,
    memberId: Long,
    bankName: String?,
    accountNumber: String?,
    accountHolder: String?,
) {
    init {
        require(meetingId > 0 && memberId > 0) { "모임/모임원 ID가 필요합니다." }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @Column(name = "meeting_id", nullable = false)
    var meetingId: Long = meetingId
        protected set

    @Column(name = "member_id", nullable = false)
    var memberId: Long = memberId
        protected set

    @Column(nullable = false, length = 100)
    var bankName: String = requireName(bankName)
        protected set

    @Column(nullable = false, length = 50)
    var accountNumber: String = requireAccountNumber(accountNumber)
        protected set

    @Column(nullable = false, length = 100)
    var accountHolder: String = requireName(accountHolder)
        protected set

    // 계좌 정보 입력
    fun update(
        bankName: String?,
        accountNumber: String?,
        accountHolder: String?,
    ) {
        val checked = SettlementAccount(meetingId, memberId, bankName, accountNumber, accountHolder)
        this.bankName = checked.bankName
        this.accountNumber = checked.accountNumber
        this.accountHolder = checked.accountHolder
    }

    companion object {
        private const val INVALID_ACCOUNT_MESSAGE = "정산 계좌 정보를 확인해주세요."
        private val ACCOUNT_NUMBER_PATTERN = Regex("[0-9]+(-[0-9]+)*")

        // 기존 String.isBlank()·strip()의 공백 판별 기준을 유지합니다.
        private fun requireName(value: String?): String {
            require(value != null && value.length <= 100 && !value.all { Character.isWhitespace(it) }) {
                INVALID_ACCOUNT_MESSAGE
            }
            return value.trim { Character.isWhitespace(it) }
        }

        private fun requireAccountNumber(value: String?): String {
            require(value != null && value.length <= 50 && value.matches(ACCOUNT_NUMBER_PATTERN)) {
                INVALID_ACCOUNT_MESSAGE
            }
            return value
        }
    }
}
