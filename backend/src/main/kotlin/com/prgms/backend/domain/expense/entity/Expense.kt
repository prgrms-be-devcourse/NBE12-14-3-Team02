package com.prgms.backend.domain.expense.entity

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapKeyColumn
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "expenses")
class Expense(
    meetingId: Long,
    payerMemberId: Long,
    title: String,
    amount: Long,
    memo: String?,
    participantIds: Set<Long>,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @Column(nullable = false)
    var meetingId: Long = meetingId
        protected set

    @Column(nullable = false)
    var payerMemberId: Long = payerMemberId
        protected set

    // 기존 String.strip()의 공백 판별 기준을 유지합니다.
    @Column(nullable = false, length = 255)
    var title: String = title.trim { Character.isWhitespace(it) }
        protected set

    @Column(nullable = false)
    var amount: Long = amount
        protected set

    @Column(length = 2000)
    var memo: String? = memo
        protected set

    // 인증된 영수증 API에서 사용하는 비공개 로컬 저장소의 파일 키 (지출당 1장)
    @Column(length = 500)
    var receiptKey: String? = null
        protected set

    // @PrePersist에서 채우며, 저장 전 응답 변환에서는 기존처럼 null을 반환합니다.
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null
        protected set

    @ElementCollection
    @CollectionTable(name = "expense_participants", joinColumns = [JoinColumn(name = "expense_id")])
    @Column(name = "member_id", nullable = false)
    private var participantIds: MutableSet<Long> = participantIds.toMutableSet()

    // 각각 얼마 내야 하는지 할당
    @ElementCollection
    @CollectionTable(name = "expense_shares", joinColumns = [JoinColumn(name = "expense_id")])
    @MapKeyColumn(name = "member_id")
    @Column(name = "share_amount", nullable = false)
    private var participantAmounts: MutableMap<Long, Long> = mutableMapOf()

    // 정산 나눌 방식 -> 지정 또는 모임 전체 인원수만큼
    @Column(length = 10)
    var splitMode: String? = null
        protected set

    // 1000,100,10단위로 금액을 나눔
    var roundingUnit: Int? = null
        protected set

    // 나머지 멤버
    var remainderMemberId: Long? = null
        protected set

    fun getParticipantIds(): Set<Long> = participantIds.toSet()

    // 정산금액
    fun getParticipantAmounts(): Map<Long, Long> = participantAmounts.toMap()

    // 결제자와 등록 시각은 유지하고, 수정한 분담 결과만 교체합니다.
    fun update(
        title: String,
        amount: Long,
        memo: String?,
        shares: Map<Long, Long>,
        splitMode: String?,
        roundingUnit: Int?,
        remainderMemberId: Long?,
    ) {
        this.title = title.trim { Character.isWhitespace(it) }
        this.amount = amount
        this.memo = memo
        participantIds.clear()
        participantIds.addAll(shares.keys)
        participantAmounts.clear()
        participantAmounts.putAll(shares)
        this.splitMode = splitMode
        this.roundingUnit = roundingUnit
        this.remainderMemberId = remainderMemberId
    }

    // null이면 영수증 첨부를 해제합니다.
    fun attachReceipt(storageKey: String?) {
        receiptKey = storageKey
    }

    @PrePersist
    protected fun onCreate() {
        createdAt = LocalDateTime.now()
    }

    companion object {
        // 정산금액 나누는 방식 n분의1, 지정 등록 -> 지정 등록의 금액 지정 기능 추가 예정
        @JvmStatic // Java의 Expense.register(...) 호출 유지
        fun register(
            meetingId: Long,
            payerMemberId: Long,
            title: String,
            amount: Long,
            memo: String?,
            shares: Map<Long, Long>,
            splitMode: String?,
            roundingUnit: Int?,
            remainderMemberId: Long?,
        ): Expense =
            Expense(meetingId, payerMemberId, title, amount, memo, shares.keys).apply {
                participantAmounts.putAll(shares)
                this.splitMode = splitMode
                this.roundingUnit = roundingUnit
                this.remainderMemberId = remainderMemberId
            }
    }
}
