package com.prgms.backend.domain.schedule.vote.service

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.candidate.entity.ScheduleCandidate
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.schedule.vote.dto.ScheduleVoteRequest
import com.prgms.backend.domain.schedule.vote.dto.ScheduleVoteResponse
import com.prgms.backend.domain.schedule.vote.entity.SchedulePreference
import com.prgms.backend.domain.schedule.vote.entity.ScheduleVote
import com.prgms.backend.domain.schedule.vote.repository.ScheduleVoteRepository
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberAlreadyLeftException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotActiveException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ScheduleVoteService(
    private val scheduleVoteRepository: ScheduleVoteRepository,
    private val meetingMemberRepository: MeetingMemberRepository,
    private val schedulePollRepository: SchedulePollRepository,
    private val meetingRepository: MeetingRepository,
) {
    @Transactional
    fun submit(
        meetingId: Long,
        candidateId: Long,
        userId: Long,
        request: ScheduleVoteRequest.Submit,
    ): ScheduleVoteResponse.Saved {
        val meeting = getMeetingWithSharedLock(meetingId)
        validateActiveMeeting(meeting)
        val schedulePoll = getPollWithSharedLock(meetingId)
        schedulePoll.validateOpen(LocalDateTime.now())
        val meetingMember = getMeetingMemberWithExclusiveLock(meetingId, userId)
        validateJoinedMember(meetingMember, meetingId, userId)
        val candidate = schedulePoll.findCandidate(candidateId)
        val savedVote = saveOrUpdateVote(candidate, meetingMember, checkNotNull(request.preference))
        return ScheduleVoteResponse.Saved.from(savedVote)
    }

    private fun saveOrUpdateVote(
        candidate: ScheduleCandidate,
        meetingMember: MeetingMember,
        preference: SchedulePreference,
    ): ScheduleVote =
        scheduleVoteRepository
            .findByScheduleCandidateIdAndMeetingMemberId(candidate.id, meetingMember.id)
            ?.apply { changePreference(preference) }
            ?: scheduleVoteRepository.save(ScheduleVote.create(candidate, meetingMember, preference))

    private fun getMeetingWithSharedLock(meetingId: Long): Meeting =
        meetingRepository.findByIdAndDeletedAtIsNullForShare(meetingId) ?: throw MeetingNotFoundException(meetingId)

    private fun getPollWithSharedLock(meetingId: Long): SchedulePoll =
        schedulePollRepository.findByMeetingIdForShare(meetingId) ?: throw SchedulePollNotFoundException(meetingId)

    private fun validateActiveMeeting(meeting: Meeting) {
        if (!meeting.isActive()) throw MeetingNotActiveException(meeting.id)
    }

    private fun getMeetingMemberWithExclusiveLock(
        meetingId: Long,
        userId: Long,
    ): MeetingMember =
        meetingMemberRepository.findByMeetingIdAndUserIdForUpdate(meetingId, userId)
            ?: throw MeetingMemberNotFoundException(meetingId, userId)

    private fun validateJoinedMember(
        meetingMember: MeetingMember,
        meetingId: Long,
        userId: Long,
    ) {
        if (!meetingMember.isJoined()) throw MeetingMemberAlreadyLeftException(meetingId, userId)
    }
}
