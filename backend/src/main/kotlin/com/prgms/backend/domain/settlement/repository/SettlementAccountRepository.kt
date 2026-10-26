package com.prgms.backend.domain.settlement.repository

import com.prgms.backend.domain.settlement.entity.SettlementAccount
import org.springframework.data.jpa.repository.JpaRepository

interface SettlementAccountRepository : JpaRepository<SettlementAccount, Long> {
    fun findByMeetingId(meetingId: Long): List<SettlementAccount>

    fun findByMeetingIdAndMemberId(
        meetingId: Long,
        memberId: Long,
    ): SettlementAccount?
}
