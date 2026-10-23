package com.prgms.backend.domain.expense.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

// 지출 삭제 후에도 요청 번호를 보존해 늦게 도착한 재요청이 지출을 복원하지 않게 합니다.
@Entity
@Table(
    name = "expense_registrations",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_expense_registration",
            columnNames = ["meeting_id", "member_id", "request_key"],
        ),
    ],
)
class ExpenseRegistration(
    meetingId: Long,
    memberId: Long,
    requestKey: String,
    fingerprint: String,
    expenseId: Long,
) {
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

    @Column(name = "request_key", nullable = false, length = 36)
    var requestKey: String = requestKey
        protected set

    @Column(nullable = false, length = 64)
    var fingerprint: String = fingerprint
        protected set

    @Column(nullable = false)
    var expenseId: Long = expenseId
        protected set
}
