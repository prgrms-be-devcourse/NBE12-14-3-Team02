package com.prgms.backend.domain.schedule.candidate.service

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.candidate.dto.ScheduleCandidateRequest
import com.prgms.backend.domain.schedule.candidate.dto.ScheduleCandidateResponse
import com.prgms.backend.domain.schedule.candidate.repository.ScheduleCandidateRepository
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.schedule.vote.repository.ScheduleVoteRepository
import com.prgms.backend.global.exception.custom.meeting.MeetingHostRequiredException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotActiveException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import com.prgms.backend.global.exception.custom.schedule.DuplicateScheduleCandidateException
import com.prgms.backend.global.exception.custom.schedule.ScheduleCandidateHasVotesException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

@Service
class ScheduleCandidateService(
    private val scheduleCandidateRepository: ScheduleCandidateRepository,
    private val schedulePollRepository: SchedulePollRepository,
    private val scheduleVoteRepository: ScheduleVoteRepository,
    private val meetingRepository: MeetingRepository,
) {
    @Transactional
    fun createCandidate(
        meetingId: Long,
        userId: Long,
        request: ScheduleCandidateRequest.Create,
    ): ScheduleCandidateResponse.Summary {
        validateActiveMeetingForHostWithSharedLock(meetingId, userId)
        val schedulePoll = getOpenPollWithExclusiveLock(meetingId)
        val candidateDate = checkNotNull(request.candidateDate)
        validateUniqueCandidateDate(schedulePoll, candidateDate)
        val candidate = schedulePoll.addCandidate(candidateDate)
        val savedCandidate = scheduleCandidateRepository.save(candidate)
        return ScheduleCandidateResponse.Summary.from(savedCandidate)
    }

    @Transactional
    fun update(
        meetingId: Long,
        candidateId: Long,
        userId: Long,
        request: ScheduleCandidateRequest.Update,
    ): ScheduleCandidateResponse.Summary {
        validateActiveMeetingForHostWithSharedLock(meetingId, userId)
        val schedulePoll = getOpenPollWithExclusiveLock(meetingId)
        val candidate = schedulePoll.findCandidate(candidateId)
        validateCandidateHasNoVotes(candidateId)
        val candidateDate = checkNotNull(request.candidateDate)
        validateUniqueCandidateDateForUpdate(schedulePoll, candidateId, candidateDate)
        candidate.updateCandidateDate(candidateDate)
        return ScheduleCandidateResponse.Summary.from(candidate)
    }

    @Transactional
    fun delete(
        meetingId: Long,
        candidateId: Long,
        userId: Long,
    ) {
        validateActiveMeetingForHostWithSharedLock(meetingId, userId)
        val schedulePoll = getOpenPollWithExclusiveLock(meetingId)
        val candidate = schedulePoll.findCandidate(candidateId)
        validateCandidateHasNoVotes(candidateId)
        schedulePoll.removeCandidate(candidate)
    }

    private fun validateCandidateHasNoVotes(candidateId: Long) {
        if (scheduleVoteRepository.existsByScheduleCandidateId(candidateId)) {
            throw ScheduleCandidateHasVotesException(candidateId)
        }
    }

    private fun getMeetingWithSharedLock(meetingId: Long): Meeting =
        meetingRepository.findByIdAndDeletedAtIsNullForShare(meetingId) ?: throw MeetingNotFoundException(meetingId)

    private fun getOpenPollWithExclusiveLock(meetingId: Long): SchedulePoll {
        val schedulePoll =
            schedulePollRepository.findByMeetingIdForUpdate(meetingId) ?: throw SchedulePollNotFoundException(meetingId)
        schedulePoll.validateOpen(LocalDateTime.now())
        return schedulePoll
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

    private fun validateUniqueCandidateDate(
        schedulePoll: SchedulePoll,
        candidateDate: LocalDate,
    ) {
        if (schedulePoll.hasDuplicateDate(candidateDate)) throw DuplicateScheduleCandidateException(candidateDate)
    }

    private fun validateActiveMeetingForHostWithSharedLock(
        meetingId: Long,
        userId: Long,
    ) {
        val meeting = getMeetingWithSharedLock(meetingId)
        validateHost(meeting, userId)
        validateActiveMeeting(meeting)
    }

    private fun validateUniqueCandidateDateForUpdate(
        schedulePoll: SchedulePoll,
        excludedCandidateId: Long,
        candidateDate: LocalDate,
    ) {
        if (schedulePoll.hasDuplicateDate(
                excludedCandidateId,
                candidateDate,
            )
        ) {
            throw DuplicateScheduleCandidateException(candidateDate)
        }
    }
}
