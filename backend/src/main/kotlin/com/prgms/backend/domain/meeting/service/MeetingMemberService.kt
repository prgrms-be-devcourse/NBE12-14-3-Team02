package com.prgms.backend.domain.meeting.service

import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.repository.ContentVoteRepository
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.meeting.dto.response.MeetingMemberResponse
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import com.prgms.backend.domain.schedule.vote.repository.ScheduleVoteRepository
import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.enums.SettlementStatus
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException
import com.prgms.backend.global.exception.custom.meeting.MeetingHostCannotLeaveException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberAlreadyLeftException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberHasUnsettledExpenseException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class MeetingMemberService(
    private val meetingMemberRepository: MeetingMemberRepository,
    private val meetingRepository: MeetingRepository,
    private val expenseRepository: ExpenseRepository,
    private val settlementRepository: SettlementRepository,
    private val scheduleVoteRepository: ScheduleVoteRepository,
    private val contentVoteRepository: ContentVoteRepository,
) {
    // meetingMemberId로 모임원 한 명 조회
    fun getMeetingMember(meetingMemberId: Long): MeetingMemberResponse {
        val meetingMember =
            meetingMemberRepository.findByIdOrNull(meetingMemberId)
                ?: throw MeetingMemberNotFoundException(meetingMemberId)
        return MeetingMemberResponse.from(meetingMember)
    }

    // 현재 모임원 목록 조회
    fun getMeetingMembers(
        meetingId: Long,
        userId: Long,
    ): List<MeetingMemberResponse> {
        if (meetingRepository.findByIdAndDeletedAtIsNull(meetingId) == null) {
            throw MeetingNotFoundException(meetingId)
        }
        if (!isMeetingMember(meetingId, userId)) {
            throw MeetingAccessDeniedException(meetingId, userId)
        }

        return meetingMemberRepository
            .findAllByMeetingIdAndStatus(meetingId, MeetingMemberStatus.JOINED)
            .map { MeetingMemberResponse.from(it) }
    }

    // JOINED 상태로 참여 중인지 확인
    fun isMeetingMember(
        meetingId: Long,
        userId: Long,
    ): Boolean =
        meetingMemberRepository.existsByMeetingIdAndUserIdAndStatus(
            meetingId,
            userId,
            MeetingMemberStatus.JOINED,
        )

    // 모임 탈퇴
    @Transactional
    fun leaveMeeting(
        meetingId: Long,
        userId: Long,
    ): MeetingMemberResponse {
        val meeting =
            meetingRepository.findByIdAndDeletedAtIsNullForUpdate(meetingId)
                ?: throw MeetingNotFoundException(meetingId)

        // 진행 중인 모임의 모임장은 탈퇴할 수 없다.
        if (meeting.isHost(userId) && !meeting.isCompleted()) {
            throw MeetingHostCannotLeaveException(meetingId, userId)
        }

        val meetingMember =
            meetingMemberRepository.findByMeetingIdAndUserId(meetingId, userId)
                ?: throw MeetingMemberNotFoundException(meetingId, userId)
        if (!meetingMember.isJoined()) {
            throw MeetingMemberAlreadyLeftException(meetingId, userId)
        }

        val meetingMemberId = meetingMember.id

        // 아직 Java인 정산 저장소의 Optional 반환값은 여기서 nullable로 변환한다.
        val settlement: Settlement? = settlementRepository.findByMeetingId(meetingId).orElse(null)
        val settlementCompleted = settlement?.status == SettlementStatus.CLOSED
        if (!settlementCompleted) {
            val involvedInExpense = expenseRepository.existsMemberInExpense(meetingId, meetingMemberId)
            if (involvedInExpense) {
                throw MeetingMemberHasUnsettledExpenseException(meetingId, userId)
            }
        }

        // 두 종류의 OPEN 투표를 같은 시각 기준으로 정리한다.
        val now = LocalDateTime.now()
        scheduleVoteRepository.deleteOpenVotesByMeetingMember(
            meetingId,
            meetingMemberId,
            SchedulePollStatus.OPEN,
            now,
        )
        contentVoteRepository.deleteOpenVotesByMeetingMember(
            meetingId = meetingId,
            meetingMemberId = meetingMemberId,
            status = ContentPollStatus.OPEN,
            now = now,
        )

        meetingMember.leave()
        return MeetingMemberResponse.from(meetingMember)
    }
}
