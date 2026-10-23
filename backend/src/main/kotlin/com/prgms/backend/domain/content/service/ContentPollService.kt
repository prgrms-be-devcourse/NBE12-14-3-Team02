package com.prgms.backend.domain.content.service

import com.prgms.backend.domain.content.dto.request.ContentPollRequest
import com.prgms.backend.domain.content.dto.response.ContentPollResponse
import com.prgms.backend.domain.content.dto.response.ContentPollResultsResponse
import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.enums.ContentPollResultSort
import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.enums.ContentPreference
import com.prgms.backend.domain.content.repository.ContentCandidateRepository
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.content.repository.ContentVoteRepository
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.global.exception.custom.content.ContentCandidateNotFoundException
import com.prgms.backend.global.exception.custom.content.ContentPollAlreadyConfirmedException
import com.prgms.backend.global.exception.custom.content.ContentPollAlreadyExistsException
import com.prgms.backend.global.exception.custom.content.ContentPollClosedException
import com.prgms.backend.global.exception.custom.content.ContentPollNotClosedException
import com.prgms.backend.global.exception.custom.content.ContentPollNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ContentPollService(
    private val contentPollRepository: ContentPollRepository,
    private val meetingRepository: MeetingRepository,
    private val contentCandidateRepository: ContentCandidateRepository,
    private val contentVoteRepository: ContentVoteRepository,
    private val meetingMemberRepository: MeetingMemberRepository,
) {
    @Transactional
    fun create(
        meetingId: Long,
        userId: Long,
        request: ContentPollRequest.Create,
    ): ContentPollResponse.Created {
        val meeting =
            meetingRepository.findByIdOrNull(meetingId)
                ?: throw MeetingNotFoundException(meetingId)
        if (!meeting.isHost(userId)) {
            throw MeetingAccessDeniedException(meetingId, userId)
        }

        if (contentPollRepository.existsByMeetingId(meetingId)) {
            throw ContentPollAlreadyExistsException(meetingId)
        }

        val saved = contentPollRepository.save(ContentPoll(meetingId, request.deadline))

        return ContentPollResponse.Created.from(saved)
    }

    @Transactional
    fun get(
        meetingId: Long,
        userId: Long,
    ): ContentPollResponse.Detail {
        val member = requireJoinedMember(meetingId, userId)

        val poll =
            contentPollRepository.findByMeetingId(meetingId)
                ?: throw ContentPollNotFoundException(meetingId)
        // 해당 poll의 후보들을 등록시간 기준으로 정렬
        val candidates = contentCandidateRepository.findByContentPollIdOrderByCreatedAtAsc(poll.id)

        // 후보 id당 선호도투표들로 매핑
        val votesByCandidate =
            contentVoteRepository
                .findByContentCandidate_ContentPoll_Id(poll.id)
                .groupBy { it.contentCandidate.id }

        val closed = poll.status == ContentPollStatus.CLOSED
        // 진행 중에는 점수와 순위를 숨기고, 마감 후에만 점수로 정렬한다.
        val ranked =
            candidates.map { candidate ->
                val votes = votesByCandidate[candidate.id].orEmpty()
                val totalScore = if (closed) votes.sumOf { it.preference.score() } else 0
                val myPreference =
                    votes
                        .firstOrNull { it.meetingMemberId == member.id }
                        ?.preference
                ContentPollResponse.CandidateRank(
                    candidate.id,
                    candidate.createdByMemberId,
                    candidate.title,
                    candidate.description,
                    totalScore,
                    myPreference,
                )
            }

        return ContentPollResponse.Detail(
            poll.id,
            poll.meetingId,
            poll.deadline,
            poll.status,
            if (closed) ranked.sortedByDescending { it.totalScore } else ranked,
        )
    }

    // 콘텐츠 투표 마감기한 업데이트
    @Transactional
    fun updateDeadline(
        meetingId: Long,
        userId: Long,
        request: ContentPollRequest.UpdateDeadline,
    ): ContentPollResponse.DeadlineUpdate {
        // 마감 스케줄러와 같은 순서로 잠금
        val meeting =
            meetingRepository.findByIdAndDeletedAtIsNullForShare(meetingId)
                ?: throw MeetingNotFoundException(meetingId)
        if (!meeting.isHost(userId)) {
            throw MeetingAccessDeniedException(meetingId, userId)
        }

        val poll =
            contentPollRepository.findByMeetingIdForUpdate(meetingId)
                ?: throw ContentPollNotFoundException(meetingId)
        if (poll.status == ContentPollStatus.CLOSED) {
            throw ContentPollClosedException()
        }
        poll.changeDeadLine(request.deadline)
        return ContentPollResponse.DeadlineUpdate.from(poll)
    }

    // 해당 미팅멤버 반환하는 함수
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

    // 콘텐츠 투표 결과 조회
    @Transactional(readOnly = true)
    fun getResults(
        meetingId: Long,
        userId: Long,
        sort: ContentPollResultSort,
    ): ContentPollResultsResponse.Detail {
        requireJoinedMember(meetingId, userId)
        val meeting =
            meetingRepository.findByIdOrNull(meetingId)
                ?: throw MeetingNotFoundException(meetingId)
        val poll =
            contentPollRepository.findByMeetingId(meetingId)
                ?: throw ContentPollNotFoundException(meetingId)
        if (poll.status != ContentPollStatus.CLOSED) {
            throw ContentPollNotClosedException("투표가 진행 중일 때는 결과를 볼 수 없습니다.")
        }

        val candidates = contentCandidateRepository.findByContentPollIdOrderByCreatedAtAsc(poll.id)

        // 모든 표들을 가져와서 후보별로 맵핑
        val allVotes = contentVoteRepository.findByContentCandidate_ContentPoll_Id(poll.id)
        val votesByCandidate = allVotes.groupBy { it.contentCandidate.id }

        // 탈퇴자 처리 기준: 현재 참여 중인 모임원 + 표를 낸 탈퇴자는 참여 인원으로 포함한다.
        // 표(점수/응답 수)와 참여 인원/미응답 수/인원별 결과가 항상 같은 모집단을 보도록 맞춘다.
        // (투표 전에 이미 탈퇴한 사람은 표가 없으므로 미응답으로 잡히지 않는다.)
        val voterIds = allVotes.map { it.meetingMemberId }.toSet()
        val allMembers = meetingMemberRepository.findAllByMeetingId(meetingId)
        val joined = allMembers.filter { it.isJoined() || it.id in voterIds }
        val joinedCount = joined.size

        // 후보 등록자가 탈퇴했어도 닉네임을 표시할 수 있도록 전체 모임원 기준으로 만든다.
        val nicknameByMemberId = allMembers.associate { it.id to it.user.nickname }

        // 후보마다 투표결과 집계 정렬기준 sort에 따라 다르게 정렬
        val candidateResults =
            candidates
                .map { candidate ->
                    val votes = votesByCandidate[candidate.id].orEmpty()
                    val responseCount = votes.size
                    ContentPollResultsResponse.CandidateResult(
                        candidate.id,
                        candidate.createdByMemberId,
                        nicknameByMemberId[candidate.createdByMemberId],
                        candidate.title,
                        candidate.description,
                        votes.sumOf { it.preference.score() },
                        votes.count { it.preference == ContentPreference.PREFER },
                        votes.count { it.preference == ContentPreference.AVAILABLE },
                        votes.count { it.preference == ContentPreference.DISLIKE },
                        responseCount,
                        joinedCount - responseCount, // 미응답 카운트
                    )
                }.sortedWith(sortComparator(sort))

        val orderedCandidateIds = candidateResults.map { it.candidateId }

        // 후보마다 사람별 투표결과 집계 (같은 후보에 표가 겹치면 먼저 나온 표를 쓴다)
        val preferenceByMemberAndCandidate =
            votesByCandidate.values
                .flatten()
                .groupBy { it.meetingMemberId }
                .mapValues { (_, votes) ->
                    votes
                        .distinctBy { it.contentCandidate.id }
                        .associate { it.contentCandidate.id to it.preference }
                }

        val memberResults =
            joined.map { member ->
                val mine = preferenceByMemberAndCandidate[member.id].orEmpty()
                val preferences = orderedCandidateIds.map { mine[it] }
                ContentPollResultsResponse.MemberResult(
                    member.id,
                    member.user.id,
                    member.user.nickname,
                    meeting.isHost(member.user.id),
                    preferences,
                    preferences.count { it != null },
                )
            }

        return ContentPollResultsResponse.Detail(
            poll.id,
            poll.meetingId,
            poll.deadline,
            poll.status,
            poll.confirmedCandidateId,
            joinedCount,
            candidateResults,
            memberResults,
        )
    }

    // 정해진 타입별로 정렬시켜주는 함수
    private fun sortComparator(sort: ContentPollResultSort): Comparator<ContentPollResultsResponse.CandidateResult> {
        val byCreated = compareBy<ContentPollResultsResponse.CandidateResult> { it.candidateId }
        return when (sort) {
            ContentPollResultSort.PARTICIPANTS ->
                compareByDescending<ContentPollResultsResponse.CandidateResult> { it.responseCount }
                    .thenByDescending { it.totalScore }
                    .then(byCreated)
            ContentPollResultSort.SCORE ->
                compareByDescending<ContentPollResultsResponse.CandidateResult> { it.totalScore }
                    .then(byCreated)
        }
    }

    // 콘텐츠 투표결과 후보 확정 시키기
    @Transactional
    fun confirm(
        meetingId: Long,
        userId: Long,
        request: ContentPollRequest.Confirm,
    ): ContentPollResponse.Confirmed {
        // 중복 확정 요청이 겹치지 않도록 Meeting 공유 잠금 -> 투표판 FOR UPDATE
        val meeting =
            meetingRepository.findByIdAndDeletedAtIsNullForShare(meetingId)
                ?: throw MeetingNotFoundException(meetingId)
        if (!meeting.isHost(userId)) {
            throw MeetingAccessDeniedException(meetingId, userId)
        }

        val poll =
            contentPollRepository.findByMeetingIdForUpdate(meetingId)
                ?: throw ContentPollNotFoundException(meetingId)
        if (poll.status != ContentPollStatus.CLOSED) {
            throw ContentPollNotClosedException()
        }
        if (poll.confirmedCandidateId != null) {
            throw ContentPollAlreadyConfirmedException()
        }

        val candidateId = checkNotNull(request.candidateId) { "검증을 통과한 요청의 후보 ID는 null일 수 없습니다." }
        val candidate =
            contentCandidateRepository.findByIdOrNull(candidateId)
                ?: throw ContentCandidateNotFoundException(candidateId)
        if (candidate.contentPoll.id != poll.id) {
            throw ContentCandidateNotFoundException(candidateId)
        }

        poll.confirm(candidateId)
        return ContentPollResponse.Confirmed.from(poll)
    }
}
