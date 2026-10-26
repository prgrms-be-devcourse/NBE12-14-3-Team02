package com.prgms.backend.domain.schedule.poll.service

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.poll.dto.SchedulePollRequest
import com.prgms.backend.domain.schedule.poll.dto.SchedulePollResponse
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.global.exception.custom.meeting.MeetingHostRequiredException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberAlreadyLeftException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotActiveException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollAlreadyExistsException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class SchedulePollService(
    private val schedulePollRepository: SchedulePollRepository,
    private val meetingRepository: MeetingRepository,
    private val meetingMemberRepository: MeetingMemberRepository,
) {
    @Transactional
    fun createPoll(
        meetingId: Long,
        userId: Long,
        request: SchedulePollRequest.Create,
    ): SchedulePollResponse.Created {
        val meeting = getMeetingWithExclusiveLock(meetingId)
        validateHost(meeting, userId)
        validateActiveMeeting(meeting)
        validateNoExistingPoll(meetingId)
        val schedulePoll = SchedulePoll.create(meeting, checkNotNull(request.deadline))
        val savedPoll = schedulePollRepository.save(schedulePoll)
        return SchedulePollResponse.Created.from(savedPoll)
    }

    @Transactional(readOnly = true)
    fun getPoll(
        meetingId: Long,
        userId: Long,
    ): SchedulePollResponse.Detail {
        validateMeetingExists(meetingId)
        validateJoinedMember(meetingId, userId)
        val schedulePoll = getPollWithCandidates(meetingId)
        return SchedulePollResponse.Detail.from(schedulePoll)
    }

    @Transactional
    fun updateDeadline(
        meetingId: Long,
        userId: Long,
        request: SchedulePollRequest.UpdateDeadline,
    ): SchedulePollResponse.DeadlineUpdate {
        val meeting = getMeetingWithSharedLock(meetingId)
        validateHost(meeting, userId)
        validateActiveMeeting(meeting)
        val schedulePoll = getPollWithExclusiveLock(meetingId)
        schedulePoll.updateDeadline(checkNotNull(request.deadline), LocalDateTime.now())
        return SchedulePollResponse.DeadlineUpdate.from(schedulePoll)
    }

    private fun validateActiveMeeting(meeting: Meeting) {
        if (!meeting.isActive()) throw MeetingNotActiveException(meeting.id)
    }

    private fun validateHost(
        meeting: Meeting,
        userId: Long,
    ) {
        if (!meeting.isHost(userId)) throw MeetingHostRequiredException()
    }

    private fun getMeetingWithExclusiveLock(meetingId: Long): Meeting =
        meetingRepository.findByIdAndDeletedAtIsNullForUpdate(meetingId) ?: throw MeetingNotFoundException(meetingId)

    private fun getMeetingWithSharedLock(meetingId: Long): Meeting =
        meetingRepository.findByIdAndDeletedAtIsNullForShare(meetingId) ?: throw MeetingNotFoundException(meetingId)

    private fun validateNoExistingPoll(meetingId: Long) {
        if (schedulePollRepository.existsByMeetingId(meetingId)) throw SchedulePollAlreadyExistsException(meetingId)
    }

    private fun validateJoinedMember(
        meetingId: Long,
        userId: Long,
    ) {
        val meetingMember =
            meetingMemberRepository.findByMeetingIdAndUserId(meetingId, userId)
                ?: throw MeetingMemberNotFoundException(meetingId, userId)
        if (!meetingMember.isJoined()) throw MeetingMemberAlreadyLeftException(meetingId, userId)
    }

    private fun validateMeetingExists(meetingId: Long) {
        if (meetingRepository.findByIdAndDeletedAtIsNull(meetingId) == null) throw MeetingNotFoundException(meetingId)
    }

    private fun getPollWithCandidates(meetingId: Long): SchedulePoll =
        schedulePollRepository.findByMeetingIdWithCandidates(meetingId)
            ?: throw SchedulePollNotFoundException(meetingId)

    private fun getPollWithExclusiveLock(meetingId: Long): SchedulePoll =
        schedulePollRepository.findByMeetingIdForUpdate(meetingId) ?: throw SchedulePollNotFoundException(meetingId)
}
