package com.prgms.backend.domain.schedule.vote.service

import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.candidate.entity.ScheduleCandidate
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.candidate
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.meeting
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.member
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.poll
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.user
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.vote
import com.prgms.backend.domain.schedule.vote.dto.ScheduleVoteRequest
import com.prgms.backend.domain.schedule.vote.entity.SchedulePreference
import com.prgms.backend.domain.schedule.vote.entity.ScheduleVote
import com.prgms.backend.domain.schedule.vote.repository.ScheduleVoteRepository
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberAlreadyLeftException
import com.prgms.backend.global.exception.custom.schedule.ScheduleCandidateNotFoundException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollClosedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleVoteServiceTest {
    private val votes = mock<ScheduleVoteRepository>()
    private val members = mock<MeetingMemberRepository>()
    private val polls = mock<SchedulePollRepository>()
    private val meetings = mock<MeetingRepository>()
    private val service =
        ScheduleVoteService(votes, members, polls, meetings)

    private lateinit var poll: SchedulePoll
    private lateinit var member: MeetingMember
    private lateinit var candidate: ScheduleCandidate

    @BeforeEach
    fun setUp() {
        val meeting = meeting(10L, user(1L, "host"))
        poll = poll(20L, meeting, LocalDateTime.now().plusDays(3))
        member = member(30L, meeting, user(2L, "member"))
        candidate = candidate(40L, poll, LocalDate.now().plusDays(1))
        whenever(meetings.findByIdAndDeletedAtIsNullForShare(10L)).thenReturn(meeting)
        whenever(polls.findByMeetingIdForShare(10L)).thenReturn(poll)
        whenever(members.findByMeetingIdAndUserIdForUpdate(10L, 2L)).thenReturn(member)
    }

    @Test
    fun `첫 응답은 Vote 한 행으로 저장하고 후보·참여자·선호도를 반환한다`() {
        whenever(votes.save(any<ScheduleVote>())).thenAnswer { invocation -> invocation.getArgument<ScheduleVote>(0) }

        val response =
            service.submit(
                10L,
                40L,
                2L,
                ScheduleVoteRequest.Submit(SchedulePreference.PREFER),
            )

        assertEquals(40L, response.candidateId)
        assertEquals(30L, response.meetingMemberId)
        assertEquals(SchedulePreference.PREFER, response.preference)
        verify(votes).save(any<ScheduleVote>())
    }

    @Test
    fun `이미 응답한 후보의 선호도를 바꾸면 기존 Vote를 수정한다`() {
        val existing = vote(candidate, member, SchedulePreference.PREFER)
        whenever(votes.findByScheduleCandidateIdAndMeetingMemberId(40L, 30L))
            .thenReturn(existing)

        val response =
            service.submit(
                10L,
                40L,
                2L,
                ScheduleVoteRequest.Submit(SchedulePreference.IMPOSSIBLE),
            )

        assertEquals(SchedulePreference.IMPOSSIBLE, existing.preference)
        assertEquals(SchedulePreference.IMPOSSIBLE, response.preference)
        verify(votes, never()).save(any())
    }

    @Test
    fun `탈퇴한 회원은 일정 후보에 응답할 수 없다`() {
        member.leave()

        assertThrows<MeetingMemberAlreadyLeftException> {
            service.submit(
                10L,
                40L,
                2L,
                ScheduleVoteRequest.Submit(SchedulePreference.PREFER),
            )
        }
        verifyNoInteractions(votes)
    }

    @Test
    fun `다른 Poll의 후보 ID는 현재 모임의 투표 대상으로 인정하지 않는다`() {
        assertThrows<ScheduleCandidateNotFoundException> {
            service.submit(
                10L,
                41L,
                2L,
                ScheduleVoteRequest.Submit(SchedulePreference.PREFER),
            )
        }
        verifyNoInteractions(votes)
    }

    @Test
    fun `마감된 Poll에는 새 응답이나 선호도 변경을 허용하지 않는다`() {
        poll.close()

        assertThrows<SchedulePollClosedException> {
            service.submit(
                10L,
                40L,
                2L,
                ScheduleVoteRequest.Submit(SchedulePreference.PREFER),
            )
        }
        verifyNoInteractions(members, votes)
    }
}
