package com.prgms.backend.domain.settlement.integration

import java.time.LocalDateTime

// 정산 트랜잭션이 커밋된 뒤 알림을 저장하기 위한 이벤트
// closedAt은 Settlement.closedAt(nullable 컬럼)을 그대로 받으므로 기존처럼 null을 허용합니다.
data class SettlementClosedEvent(
    val meetingId: Long,
    val settlementId: Long,
    val closedAt: LocalDateTime?,
)
