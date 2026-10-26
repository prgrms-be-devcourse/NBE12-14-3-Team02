package com.prgms.backend.domain.settlement.repository

import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.enums.SettlementStatus
import org.springframework.data.jpa.repository.JpaRepository

interface SettlementRepository : JpaRepository<Settlement, Long> {
    fun findByMeetingId(meetingId: Long): Settlement?

    fun existsByMeetingIdAndStatus(
        meetingId: Long,
        status: SettlementStatus,
    ): Boolean
}
