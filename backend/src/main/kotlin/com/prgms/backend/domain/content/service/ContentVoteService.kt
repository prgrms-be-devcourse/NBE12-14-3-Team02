package com.prgms.backend.domain.content.service

import com.prgms.backend.domain.content.dto.request.ContentVoteRequest
import com.prgms.backend.domain.content.dto.response.ContentVoteResponse
import com.prgms.backend.domain.content.entity.ContentCandidate
import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.entity.ContentVote
import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.repository.ContentCandidateRepository
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.content.repository.ContentVoteRepository
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.global.exception.custom.content.ContentCandidateNotFoundException
import com.prgms.backend.global.exception.custom.content.ContentPollClosedException
import com.prgms.backend.global.exception.custom.content.ContentPollNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberNotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ContentVoteService(
    private val contentVoteRepository: ContentVoteRepository,
    private val contentCandidateRepository: ContentCandidateRepository,
    private val contentPollRepository: ContentPollRepository,
    private val meetingMemberRepository: MeetingMemberRepository,
) {
    @Transactional
    fun upsert(
        meetingId: Long,
        userId: Long,
        request: ContentVoteRequest.Submit,
    ): ContentVoteResponse.Saved {
        val member = requireJoinedMember(meetingId, userId)

        val poll = getOpenPoll(meetingId)
        val candidateId = checkNotNull(request.candidateId) { "검증을 통과한 요청의 후보 ID는 null일 수 없습니다." }
        val candidate = getCandidateInPoll(candidateId, poll)
        val preference = checkNotNull(request.preference) { "검증을 통과한 요청의 선호도는 null일 수 없습니다." }

        // 투표가 있으면 변경, 없으면 추가
        val vote =
            contentVoteRepository
                .findByContentCandidateIdAndMeetingMemberId(candidate.id, member.id)
                ?.apply { changePreference(preference) }
                ?: contentVoteRepository.save(ContentVote(candidate, member.id, preference))

        return ContentVoteResponse.Saved.from(vote)
    }

    private fun getOpenPoll(meetingId: Long): ContentPoll {
        val poll =
            contentPollRepository.findByMeetingId(meetingId)
                ?: throw ContentPollNotFoundException(meetingId)
        if (poll.status == ContentPollStatus.CLOSED) {
            throw ContentPollClosedException()
        }
        return poll
    }

    private fun getCandidateInPoll(
        candidateId: Long,
        poll: ContentPoll,
    ): ContentCandidate {
        val candidate =
            contentCandidateRepository.findByIdOrNull(candidateId)
                ?: throw ContentCandidateNotFoundException(candidateId)
        if (candidate.contentPoll.id != poll.id) {
            throw ContentCandidateNotFoundException(candidateId)
        }
        return candidate
    }

    private fun requireJoinedMember(
        meetingId: Long,
        userId: Long,
    ): MeetingMember {
        val member =
            meetingMemberRepository.findByMeetingIdAndUserId(meetingId, userId)
                ?: throw MeetingMemberNotFoundException(meetingId, userId)
        if (!member.isJoined()) {
            throw MeetingAccessDeniedException(meetingId, userId)
        }
        return member
    }
}
