package com.prgms.backend.domain.settlement.service

import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.settlement.dto.SettlementResponse
import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.enums.SettlementStatus
import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.global.exception.custom.settlement.SettlementRequestException
import jakarta.persistence.EntityManager
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.Principal

@Service
class SettlementHistoryService(
    private val entityManager: EntityManager,
) {
    data class Entry(
        val meetingName: String,
        val currentMemberId: Long,
        val names: Map<Long, String>,
        val result: SettlementResponse,
    )

    @Transactional(readOnly = true)
    fun getMine(principal: Principal?): List<Entry> {
        val user =
            (principal as? Authentication)
                ?.takeIf { it.isAuthenticated }
                ?.principal as? SecurityUser
                ?: throw SettlementRequestException(401, "로그인이 필요합니다.")
        // 탈퇴 여부와 무관하게 로그인 사용자 자신의 과거 모임 소속을 확인합니다.
        val memberships: List<MeetingMember> =
            entityManager
                .createQuery(
                    """
                    select m from MeetingMember m join fetch m.meeting
                    where m.user.id = :userId and m.meeting.deletedAt is null
                    """.trimIndent(),
                    MeetingMember::class.java,
                ).setParameter("userId", user.id)
                .resultList
        if (memberships.isEmpty()) return emptyList()
        val byMeeting = memberships.associateBy { it.meeting.id }
        val settlements: List<Settlement> =
            entityManager
                .createQuery(
                    """
                    select distinct s from Settlement s left join fetch s.balances
                    where s.meetingId in :ids and s.status = :status order by s.closedAt desc
                    """.trimIndent(),
                    Settlement::class.java,
                ).setParameter("ids", byMeeting.keys)
                .setParameter("status", SettlementStatus.CLOSED)
                .resultList
        if (settlements.isEmpty()) return emptyList()
        val ids = settlements.map { it.id }
        // 여러 컬렉션을 한 번에 곱집합으로 가져오지 않고, 컬렉션별로 일괄 조회합니다.
        entityManager
            .createQuery(
                "select distinct s from Settlement s left join fetch s.transfers where s.id in :ids",
                Settlement::class.java,
            ).setParameter("ids", ids)
            .resultList
        entityManager
            .createQuery(
                "select distinct s from Settlement s left join fetch s.accounts where s.id in :ids",
                Settlement::class.java,
            ).setParameter("ids", ids)
            .resultList
        val entries =
            settlements.mapNotNull { settlement ->
                val member = byMeeting.getValue(settlement.meetingId)
                val personal = SettlementResponse.forMember(settlement, member.id, true)
                if (personal.balances.isEmpty()) {
                    null
                } else {
                    Entry(
                        meetingName = member.meeting.name,
                        currentMemberId = member.id,
                        names = emptyMap(),
                        result = personal,
                    )
                }
            }
        val visibleMembers = entries.flatMap { it.result.transferMemberIds() }.toSet()
        val names: Map<Long, String> =
            if (visibleMembers.isEmpty()) {
                emptyMap()
            } else {
                entityManager
                    .createQuery(
                        "select m from MeetingMember m join fetch m.user where m.id in :ids",
                        MeetingMember::class.java,
                    ).setParameter("ids", visibleMembers)
                    .resultList
                    .associate { it.id to it.user.nickname }
            }
        return entries.map { entry ->
            entry.copy(
                names = entry.result.transferMemberIds().associateWith { names[it] ?: "모임원 #$it" },
            )
        }
    }

    private fun SettlementResponse.transferMemberIds(): List<Long> =
        transfers.flatMap { listOf(it.senderId, it.recipientId) }
}
