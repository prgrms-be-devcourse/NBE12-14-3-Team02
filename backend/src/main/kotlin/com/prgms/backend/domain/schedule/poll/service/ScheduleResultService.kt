package com.prgms.backend.domain.schedule.poll.service

import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.candidate.entity.ScheduleCandidate
import com.prgms.backend.domain.schedule.poll.dto.ScheduleParticipantResponse
import com.prgms.backend.domain.schedule.poll.dto.ScheduleResultResponse
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.schedule.vote.entity.SchedulePreference
import com.prgms.backend.domain.schedule.vote.entity.ScheduleVote
import com.prgms.backend.domain.schedule.vote.repository.ScheduleVoteRepository
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberAlreadyLeftException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollNotClosedException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class ScheduleResultService(
    private val schedulePollRepository: SchedulePollRepository,
    private val meetingMemberRepository: MeetingMemberRepository,
    private val scheduleVoteRepository: ScheduleVoteRepository,
    private val meetingRepository: MeetingRepository,
) {
    private data class CandidateAggregation(
        val candidateId: Long,
        val candidateDate: LocalDate,
        val totalScore: Int,
        val responseCount: Long,
        val nonResponseCount: Long,
        val preferenceCounts: ScheduleResultResponse.PreferenceCounts,
    )

    private data class ResultContext(
        val schedulePoll: SchedulePoll,
        val joinedMembers: List<MeetingMember>,
        val votes: List<ScheduleVote>,
    )

    @Transactional(readOnly = true)
    fun getResults(
        meetingId: Long,
        userId: Long,
    ): ScheduleResultResponse.Detail {
        val context = getResultContext(meetingId, userId)
        val candidateRanks = createCandidateRanks(context)
        val participantResponses = createParticipantResponses(context)
        return ScheduleResultResponse.Detail(candidateRanks, participantResponses)
    }

    private fun createCandidateRanks(context: ResultContext): List<ScheduleResultResponse.CandidateRank> {
        val votesByCandidate = context.votes.groupBy { it.scheduleCandidate.id }
        val joinedMemberCount = context.joinedMembers.size.toLong()
        val aggregations =
            context.schedulePoll.getCandidates().map { candidate ->
                val candidateVotes = votesByCandidate[candidate.id].orEmpty()
                val totalScore = candidateVotes.sumOf { it.getScore() }
                val counts =
                    ScheduleResultResponse.PreferenceCounts(
                        countPreference(candidateVotes, SchedulePreference.PREFER),
                        countPreference(candidateVotes, SchedulePreference.AVAILABLE),
                        countPreference(candidateVotes, SchedulePreference.DISLIKE),
                        countPreference(candidateVotes, SchedulePreference.IMPOSSIBLE),
                    )
                val responseCount = candidateVotes.size.toLong()
                CandidateAggregation(
                    candidate.id,
                    candidate.candidateDate,
                    totalScore,
                    responseCount,
                    joinedMemberCount - responseCount,
                    counts,
                )
            }
        val sorted =
            aggregations.sortedWith(
                compareByDescending<CandidateAggregation> {
                    it.totalScore
                }.thenBy { it.candidateDate },
            )
        var previousScore: Int? = null
        var currentRank = 0
        return sorted.mapIndexed { index, aggregation ->
            if (previousScore == null || aggregation.totalScore != previousScore) {
                currentRank = index + 1
                previousScore = aggregation.totalScore
            }
            ScheduleResultResponse.CandidateRank(
                aggregation.candidateId,
                aggregation.candidateDate,
                aggregation.totalScore,
                currentRank,
                aggregation.responseCount,
                aggregation.nonResponseCount,
                aggregation.preferenceCounts,
            )
        }
    }

    private fun createParticipantResponses(context: ResultContext): List<ScheduleParticipantResponse.MemberResponses> {
        val votesByMember =
            context.votes.groupBy { it.meetingMember.id }.mapValues { (_, votes) ->
                // 기존 Collectors.toMap과 동일하게 중복 후보 키는 허용하지 않는다.
                val byCandidate = mutableMapOf<Long, ScheduleVote>()
                votes.forEach { vote ->
                    check(byCandidate.put(vote.scheduleCandidate.id, vote) == null) { "Duplicate candidate vote" }
                }
                byCandidate
            }
        val candidates = context.schedulePoll.getCandidates().sortedBy { it.candidateDate }
        return context.joinedMembers.sortedBy { it.id }.map { member ->
            toMemberResponses(member, candidates, votesByMember[member.id].orEmpty())
        }
    }

    private fun getResultContext(
        meetingId: Long,
        userId: Long,
    ): ResultContext {
        if (meetingRepository.findByIdAndDeletedAtIsNull(meetingId) == null) throw MeetingNotFoundException(meetingId)
        validateJoinedMember(meetingId, userId)
        val schedulePoll = getClosedSchedulePoll(meetingId)
        val joinedMembers = meetingMemberRepository.findAllByMeetingIdAndStatus(meetingId, MeetingMemberStatus.JOINED)
        val joinedMemberIds = joinedMembers.map { it.id }.toSet()
        val votes =
            scheduleVoteRepository
                .findAllByScheduleCandidateSchedulePollId(schedulePoll.id)
                .filter { it.meetingMember.id in joinedMemberIds }
        return ResultContext(schedulePoll, joinedMembers, votes)
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

    private fun getClosedSchedulePoll(meetingId: Long): SchedulePoll {
        val schedulePoll =
            schedulePollRepository.findByMeetingId(meetingId) ?: throw SchedulePollNotFoundException(meetingId)
        if (schedulePoll.status != SchedulePollStatus.CLOSED) throw SchedulePollNotClosedException()
        return schedulePoll
    }

    private fun toMemberResponses(
        member: MeetingMember,
        candidates: List<ScheduleCandidate>,
        votesByCandidate: Map<Long, ScheduleVote>,
    ): ScheduleParticipantResponse.MemberResponses {
        val answers =
            candidates.map { candidate ->
                ScheduleParticipantResponse.CandidateAnswer(
                    candidate.id,
                    candidate.candidateDate,
                    votesByCandidate[candidate.id]?.preference,
                )
            }
        return ScheduleParticipantResponse.MemberResponses(member.id, member.user.id, member.user.nickname, answers)
    }

    private fun countPreference(
        votes: List<ScheduleVote>,
        preference: SchedulePreference,
    ): Long = votes.count { it.preference == preference }.toLong()
}
