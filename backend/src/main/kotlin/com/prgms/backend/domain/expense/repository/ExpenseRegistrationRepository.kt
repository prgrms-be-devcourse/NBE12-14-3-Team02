package com.prgms.backend.domain.expense.repository

import com.prgms.backend.domain.expense.entity.ExpenseRegistration
import org.springframework.data.jpa.repository.JpaRepository

interface ExpenseRegistrationRepository : JpaRepository<ExpenseRegistration, Long> {
    fun findByMeetingIdAndMemberIdAndRequestKey(
        meetingId: Long,
        memberId: Long,
        requestKey: String,
    ): ExpenseRegistration?
}
