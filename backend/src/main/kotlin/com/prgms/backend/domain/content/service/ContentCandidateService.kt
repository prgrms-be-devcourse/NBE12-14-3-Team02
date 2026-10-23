package com.prgms.backend.domain.content.service

import com.prgms.backend.domain.content.dto.request.ContentCandidateRequest
import com.prgms.backend.domain.content.dto.response.ContentCandidateResponse
import com.prgms.backend.domain.content.entity.ContentCandidate
import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.repository.ContentCandidateRepository
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.content.repository.ContentVoteRepository
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.global.exception.custom.content.ContentCandidateHasVotesException
import com.prgms.backend.global.exception.custom.content.ContentCandidateNotFoundException
import com.prgms.backend.global.exception.custom.content.ContentPollClosedException
import com.prgms.backend.global.exception.custom.content.ContentPollNotFoundException
import com.prgms.backend.global.exception.custom.content.DuplicateContentCandidateTitleException
import com.prgms.backend.global.exception.custom.content.NotContentCandidateOwnerException
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberNotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ContentCandidateService(
    private val contentCandidateRepository: ContentCandidateRepository,
    private val contentPollRepository: ContentPollRepository,
    private val contentVoteRepository: ContentVoteRepository,
    private val meetingMemberRepository: MeetingMemberRepository,
) {
    // 후보 생성
    @Transactional
    fun create(
        meetingId: Long,
        userId: Long,
        request: ContentCandidateRequest.Create,
    ): ContentCandidateResponse.Saved {
        val member = requireJoinedMember(meetingId, userId)
        val poll = getOpenPoll(meetingId)
        val title = checkNotNull(request.title) { "검증을 통과한 요청의 제목은 null일 수 없습니다." }

        if (contentCandidateRepository.existsByContentPollIdAndTitle(poll.id, title)) {
            throw DuplicateContentCandidateTitleException(title)
        }

        val saved =
            contentCandidateRepository.save(
                ContentCandidate(poll, member.id, title, request.description),
            )
        return ContentCandidateResponse.Saved.from(saved)
    }

    // 후보 수정
    @Transactional
    fun update(
        meetingId: Long,
        candidateId: Long,
        userId: Long,
        request: ContentCandidateRequest.Update,
    ): ContentCandidateResponse.Saved {
        val member = requireJoinedMember(meetingId, userId)

        val poll = getOpenPoll(meetingId)
        val candidate = getCandidateInPoll(candidateId, poll)

        // 접근 meetingMemberId가 후보생성 멤버가 아니면 예외
        if (candidate.createdByMemberId != member.id) {
            throw NotContentCandidateOwnerException()
        }
        // 후보가 투표가 되어있으면 예외
        if (contentVoteRepository.existsByContentCandidateId(candidateId)) {
            throw ContentCandidateHasVotesException(candidateId)
        }
        val title = checkNotNull(request.title) { "검증을 통과한 요청의 제목은 null일 수 없습니다." }
        // 수정하려는 제목이 다른 후보와 중복되면 예외
        if (contentCandidateRepository.existsByContentPollIdAndTitleAndIdNot(poll.id, title, candidateId)) {
            throw DuplicateContentCandidateTitleException(title)
        }

        candidate.update(title, request.description)
        return ContentCandidateResponse.Saved.from(candidate)
    }

    @Transactional
    fun delete(
        meetingId: Long,
        candidateId: Long,
        userId: Long,
    ) {
        val member = requireJoinedMember(meetingId, userId)

        val poll = getOpenPoll(meetingId)
        val candidate = getCandidateInPoll(candidateId, poll)

        // 멤버 ID가 후보만든 멤버가 아니면 예외
        if (candidate.createdByMemberId != member.id) {
            throw NotContentCandidateOwnerException()
        }
        // 후보가 투표되어있으면 예외
        if (contentVoteRepository.existsByContentCandidateId(candidateId)) {
            throw ContentCandidateHasVotesException(candidateId)
        }
        contentCandidateRepository.delete(candidate)
    }

    // 열려있는 투표판을 가져옴
    private fun getOpenPoll(meetingId: Long): ContentPoll {
        val poll =
            contentPollRepository.findByMeetingId(meetingId)
                ?: throw ContentPollNotFoundException(meetingId)
        if (poll.status == ContentPollStatus.CLOSED) {
            throw ContentPollClosedException()
        }
        return poll
    }

    // 투표판안의 후보를 가져옴
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
