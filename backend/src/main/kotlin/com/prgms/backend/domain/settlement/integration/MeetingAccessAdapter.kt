package com.prgms.backend.domain.settlement.integration

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.global.exception.custom.settlement.SettlementRequestException
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import java.security.Principal

@Component
class MeetingAccessAdapter(
    private val entityManager: EntityManager,
    private val memberRepository: MeetingMemberRepository,
) : MeetingAccessPort {
    override fun requireMember(
        meetingId: Long,
        principal: Principal?,
    ): MeetingAccessPort.Context = requireMember(meetingId, principal, true)

    override fun requireMemberForRead(
        meetingId: Long,
        principal: Principal?,
    ): MeetingAccessPort.Context = requireMember(meetingId, principal, false)

    private fun requireMember(
        meetingId: Long,
        principal: Principal?,
        lock: Boolean,
    ): MeetingAccessPort.Context {
        // 로그인해서 모임에 참여한 사람인지 검증
        val loginUser =
            (principal as? Authentication)
                ?.takeIf { it.isAuthenticated }
                ?.principal as? SecurityUser
                ?: throw SettlementRequestException(401, "로그인이 필요합니다.")
        val userId = loginUser.id

        // 아직 정산 데이터가 없어도 존재하는 모임 행을 잠가 최초 확정 요청
        val meeting: Meeting =
            findMeeting(meetingId, lock)
                ?.takeUnless { it.isDeleted() }
                ?: throw SettlementRequestException(404, "모임을 찾을 수 없습니다.")
        val member =
            memberRepository
                .findByMeetingIdAndUserId(meetingId, userId)
                ?.takeIf { it.isJoined() }
                ?: throw SettlementRequestException(403, "모임 참여자만 이용할 수 있습니다.")

        // 탈퇴한 사람도 과거 지출의 결제자나 부담자일 경우 정산 대상에는 포함
        val memberIds: Set<Long> =
            entityManager
                .createQuery(
                    "select m.id from MeetingMember m where m.meeting.id = :meetingId",
                    Long::class.javaObjectType,
                ).setParameter("meetingId", meetingId)
                .resultList
                .toSet()
        return MeetingAccessPort.Context(member.id, meeting.isHost(userId), meeting.isActive(), memberIds)
    }

    private fun findMeeting(
        meetingId: Long,
        lock: Boolean,
    ): Meeting? =
        if (lock) {
            entityManager.find(Meeting::class.java, meetingId, LockModeType.PESSIMISTIC_WRITE)
        } else {
            entityManager.find(Meeting::class.java, meetingId)
        }
}
