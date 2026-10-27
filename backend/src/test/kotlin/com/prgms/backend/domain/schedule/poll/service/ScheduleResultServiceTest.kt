package com.prgms.backend.domain.schedule.poll.service

import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.candidate
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.meeting
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.member
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.poll
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.user
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.vote
import com.prgms.backend.domain.schedule.vote.entity.SchedulePreference
import com.prgms.backend.domain.schedule.vote.repository.ScheduleVoteRepository
import com.prgms.backend.global.exception.custom.schedule.SchedulePollNotClosedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleResultServiceTest {
    private val polls = mock<SchedulePollRepository>()
    private val members = mock<MeetingMemberRepository>()
    private val votes = mock<ScheduleVoteRepository>()
    private val meetings = mock<MeetingRepository>()
    private val service =
        ScheduleResultService(polls, members, votes, meetings)

    private lateinit var poll: SchedulePoll
    private lateinit var host: MeetingMember

    @BeforeEach
    fun setUp() {
        val meeting = meeting(10L, user(1L, "host"))
        poll = poll(20L, meeting, LocalDateTime.now().plusDays(3))
        host = member(30L, meeting, meeting.host)
        whenever(meetings.findByIdAndDeletedAtIsNull(10L)).thenReturn(meeting)
        whenever(members.findByMeetingIdAndUserId(10L, 1L)).thenReturn(host)
        whenever(polls.findByMeetingId(10L)).thenReturn(poll)
    }

    @Test
    fun `마감 결과는 총점·공동 순위·0점 응답·미응답을 구분하고 탈퇴자 표를 제외한다`() {
        poll.close()
        val firstDate = LocalDate.now().plusDays(1)
        val first = candidate(40L, poll, firstDate)
        val second = candidate(41L, poll, firstDate.plusDays(1))
        val unanswered = candidate(42L, poll, firstDate.plusDays(2))
        val meeting = poll.meeting
        val secondMember = member(31L, meeting, user(2L, "second"))
        val thirdMember = member(32L, meeting, user(3L, "third"))
        val leftMember = member(33L, meeting, user(4L, "left"))
        leftMember.leave()

        whenever(members.findAllByMeetingIdAndStatus(10L, MeetingMemberStatus.JOINED))
            .thenReturn(listOf(host, secondMember, thirdMember))
        whenever(votes.findAllByScheduleCandidateSchedulePollId(20L)).thenReturn(
            listOf(
                vote(first, host, SchedulePreference.PREFER),
                vote(first, secondMember, SchedulePreference.IMPOSSIBLE),
                vote(second, host, SchedulePreference.AVAILABLE),
                vote(second, secondMember, SchedulePreference.DISLIKE),
                vote(unanswered, leftMember, SchedulePreference.PREFER),
            ),
        )

        val result = service.getResults(10L, 1L)

        assertEquals(listOf(40L, 41L, 42L), result.candidateRanks.map { it.candidateId })
        assertEquals(listOf(1, 1, 3), result.candidateRanks.map { it.rank })
        assertEquals(listOf(3, 3, 0), result.candidateRanks.map { it.totalScore })
        assertEquals(2L, result.candidateRanks.first().responseCount)
        assertEquals(1L, result.candidateRanks.first().nonResponseCount)
        assertEquals(
            1L,
            result.candidateRanks
                .first()
                .preferenceCounts.impossible,
        )
        assertEquals(0L, result.candidateRanks.last().responseCount)
        assertEquals(3L, result.candidateRanks.last().nonResponseCount)

        assertEquals(3, result.participantResponses.size)
        assertEquals(
            SchedulePreference.PREFER,
            result.participantResponses
                .first()
                .answers
                .first()
                .preference,
        )
        assertEquals(
            SchedulePreference.IMPOSSIBLE,
            result.participantResponses[1]
                .answers
                .first()
                .preference,
        )
        assertNull(
            result.participantResponses
                .last()
                .answers
                .first()
                .preference,
        )
    }

    @Test
    fun `열린 투표에서는 순위와 참여자별 결과를 공개하지 않는다`() {
        assertThrows<SchedulePollNotClosedException> { service.getResults(10L, 1L) }
        verifyNoInteractions(votes)
    }
}
