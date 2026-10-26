package com.prgms.backend.domain.settlement.integration

import java.security.Principal

// 로그인 사용자와 모임 정보 연결
interface MeetingAccessPort {
    fun requireMember(
        meetingId: Long,
        principal: Principal?,
    ): Context

    fun requireMemberForRead(
        meetingId: Long,
        principal: Principal?,
    ): Context

    // 과거에 탈퇴헌 멤버도 포함
    data class Context(
        val memberId: Long,
        val leader: Boolean,
        val meetingOpen: Boolean,
        val memberIds: Set<Long>,
    )
}
