package com.prgms.backend.domain.meeting.service

import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.meeting.dto.request.MeetingCreateRequest
import com.prgms.backend.domain.meeting.dto.request.MeetingUpdateRequest
import com.prgms.backend.domain.meeting.dto.response.MeetingResponse
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.settlement.enums.SettlementStatus
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.repository.getUser
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotActiveException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingPollNotCompletedException
import com.prgms.backend.global.exception.custom.meeting.MeetingSettlementNotCompletedException
import com.prgms.backend.global.exception.custom.user.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MeetingService(
    private val meetingRepository: MeetingRepository,
    private val meetingMemberRepository: MeetingMemberRepository,
    private val userRepository: UserRepository,
    private val settlementRepository: SettlementRepository,
    private val expenseRepository: ExpenseRepository,
    private val schedulePollRepository: SchedulePollRepository,
    private val contentPollRepository: ContentPollRepository,
) {
    // 모임 객체 생성
    @Transactional
    fun createMeeting(
        userId: Long,
        request: MeetingCreateRequest,
    ): MeetingResponse {
        // 기존 회원 조회와 예외 메시지를 유지한다.
        val host = userRepository.getUser(userId, "존재하지 않는 회원입니다.")
        val meeting = Meeting(host, checkNotNull(request.name), request.description)
        val savedMeeting = meetingRepository.save(meeting)

        // 생성자를 모임장인 동시에 모임원으로 등록한다.
        val hostMember = MeetingMember(savedMeeting, host)
        meetingMemberRepository.save(hostMember)

        return toResponse(savedMeeting)
    }

    // ACTIVE/COMPLETED 모임을 조회할 수 있으며, 삭제된 모임과 현재 모임원이 아닌 사용자는 차단한다.
    @Transactional(readOnly = true)
    fun getMeeting(
        meetingId: Long,
        userId: Long,
    ): MeetingResponse {
        val meeting =
            meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                ?: throw MeetingNotFoundException(meetingId)

        val isMember =
            meetingMemberRepository.existsByMeetingIdAndUserIdAndStatus(
                meetingId,
                userId,
                MeetingMemberStatus.JOINED,
            )
        if (!isMember) {
            throw MeetingAccessDeniedException(meetingId, userId)
        }

        return toResponse(meeting)
    }

    // 참여 중이며 삭제되지 않은 모임 목록 조회
    @Transactional(readOnly = true)
    fun getMyMeetings(userId: Long): List<MeetingResponse> {
        if (!userRepository.existsById(userId)) {
            throw UserNotFoundException("존재하지 않는 회원입니다.")
        }

        val meetingMembers =
            meetingMemberRepository.findAllByUserIdAndStatusAndMeetingDeletedAtIsNull(
                userId,
                MeetingMemberStatus.JOINED,
            )
        if (meetingMembers.isEmpty()) {
            return emptyList()
        }

        val meetingIds = meetingMembers.map { it.meeting.id }

        // 기존 GROUP BY 일괄 조회를 유지하여 모임별 추가 조회를 막는다.
        val participantCountMap =
            meetingMemberRepository
                .countByMeetingIdsAndStatus(meetingIds, MeetingMemberStatus.JOINED)
                .associate { row -> (row[0] as Long) to (row[1] as Long) }

        return meetingMembers.map { member ->
            val meeting = member.meeting
            MeetingResponse.from(meeting, participantCountMap.getOrDefault(meeting.id, 0L))
        }
    }

    // 모임 수정
    @Transactional
    fun updateMeeting(
        meetingId: Long,
        userId: Long,
        request: MeetingUpdateRequest,
    ): MeetingResponse {
        val meeting =
            meetingRepository.findByIdAndDeletedAtIsNullForUpdate(meetingId)
                ?: throw MeetingNotFoundException(meetingId)

        if (!meeting.isHost(userId)) {
            throw MeetingAccessDeniedException(meetingId, userId)
        }
        if (!meeting.isActive()) {
            throw MeetingNotActiveException(meetingId)
        }

        meeting.update(checkNotNull(request.name), request.description)
        return toResponse(meeting)
    }

    // 모임 종료
    @Transactional
    fun completeMeeting(
        meetingId: Long,
        userId: Long,
    ): MeetingResponse {
        // 모임 종료와 다른 상태 변경 요청을 직렬화하는 기존 WRITE LOCK을 유지한다.
        val meeting =
            meetingRepository.findByIdAndDeletedAtIsNullForUpdate(meetingId)
                ?: throw MeetingNotFoundException(meetingId)

        if (!meeting.isHost(userId)) {
            throw MeetingAccessDeniedException(meetingId, userId)
        }
        if (!meeting.isActive()) {
            throw MeetingNotActiveException(meetingId)
        }

        val hasOpenSchedulePoll =
            schedulePollRepository.existsByMeetingIdAndStatus(meetingId, SchedulePollStatus.OPEN)
        val hasOpenContentPoll =
            contentPollRepository.existsByMeetingIdAndStatus(meetingId, ContentPollStatus.OPEN)
        if (hasOpenSchedulePoll || hasOpenContentPoll) {
            throw MeetingPollNotCompletedException(meetingId)
        }

        val hasExpense = expenseRepository.existsByMeetingId(meetingId)
        if (hasExpense && !settlementRepository.existsByMeetingIdAndStatus(meetingId, SettlementStatus.CLOSED)) {
            throw MeetingSettlementNotCompletedException(meetingId)
        }

        meeting.complete()
        return toResponse(meeting)
    }

    private fun toResponse(meeting: Meeting): MeetingResponse {
        val participantCount =
            meetingMemberRepository.countByMeetingIdAndStatus(
                meeting.id,
                MeetingMemberStatus.JOINED,
            )
        return MeetingResponse.from(meeting, participantCount)
    }
}
