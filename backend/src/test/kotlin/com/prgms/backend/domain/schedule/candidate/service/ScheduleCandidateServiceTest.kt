package com.prgms.backend.domain.schedule.candidate.service

import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.candidate.dto.ScheduleCandidateRequest
import com.prgms.backend.domain.schedule.candidate.entity.ScheduleCandidate
import com.prgms.backend.domain.schedule.candidate.repository.ScheduleCandidateRepository
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.candidate
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.meeting
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.poll
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.user
import com.prgms.backend.domain.schedule.vote.repository.ScheduleVoteRepository
import com.prgms.backend.global.exception.custom.meeting.MeetingHostRequiredException
import com.prgms.backend.global.exception.custom.schedule.DuplicateScheduleCandidateException
import com.prgms.backend.global.exception.custom.schedule.ScheduleCandidateHasVotesException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollClosedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleCandidateServiceTest {
    private val candidates = mock<ScheduleCandidateRepository>()
    private val polls = mock<SchedulePollRepository>()
    private val votes = mock<ScheduleVoteRepository>()
    private val meetings = mock<MeetingRepository>()
    private val service =
        ScheduleCandidateService(candidates, polls, votes, meetings)

    private lateinit var poll: SchedulePoll
    private lateinit var firstDate: LocalDate

    @BeforeEach
    fun setUp() {
        val meeting = meeting(10L, user(1L, "host"))
        poll = poll(20L, meeting, LocalDateTime.now().plusDays(3))
        firstDate = LocalDate.now().plusDays(1)
        whenever(meetings.findByIdAndDeletedAtIsNullForShare(10L)).thenReturn(meeting)
        whenever(polls.findByMeetingIdForUpdate(10L)).thenReturn(poll)
    }

    @Test
    fun `모임장이 후보를 추가하면 Poll 목록과 응답에 새 후보가 반영된다`() {
        whenever(candidates.save(any<ScheduleCandidate>())).thenAnswer { invocation ->
            val saved = invocation.getArgument<ScheduleCandidate>(0)
            ReflectionTestUtils.setField(saved, "id", 40L)
            saved
        }

        val response = service.createCandidate(10L, 1L, ScheduleCandidateRequest.Create(firstDate))

        assertEquals(40L, response.id)
        assertEquals(firstDate, response.candidateDate)
        assertEquals(1, poll.getCandidates().size)
        assertEquals(poll, poll.getCandidates().first().schedulePoll)
    }

    @Test
    fun `같은 투표에 이미 있는 날짜는 후보로 다시 추가하지 않는다`() {
        candidate(40L, poll, firstDate)

        assertThrows<DuplicateScheduleCandidateException> {
            service.createCandidate(10L, 1L, ScheduleCandidateRequest.Create(firstDate))
        }

        assertEquals(1, poll.getCandidates().size)
        verify(candidates, never()).save(any())
    }

    @Test
    fun `응답이 없는 후보는 다른 날짜로 수정하고 삭제할 수 있다`() {
        val candidate = candidate(40L, poll, firstDate)
        val changed = firstDate.plusDays(1)

        val response = service.update(10L, 40L, 1L, ScheduleCandidateRequest.Update(changed))
        assertEquals(changed, response.candidateDate)
        assertEquals(changed, candidate.candidateDate)

        service.delete(10L, 40L, 1L)
        assertTrue(poll.getCandidates().isEmpty())
    }

    @Test
    fun `다른 후보와 날짜가 같아지는 수정은 거부한다`() {
        val first = candidate(40L, poll, firstDate)
        val occupied = firstDate.plusDays(1)
        candidate(41L, poll, occupied)

        assertThrows<DuplicateScheduleCandidateException> {
            service.update(10L, 40L, 1L, ScheduleCandidateRequest.Update(occupied))
        }
        assertEquals(firstDate, first.candidateDate)
    }

    @Test
    fun `응답이 있는 후보는 삭제할 수 없다`() {
        candidate(40L, poll, firstDate)
        whenever(votes.existsByScheduleCandidateId(40L)).thenReturn(true)

        assertThrows<ScheduleCandidateHasVotesException> { service.delete(10L, 40L, 1L) }

        assertEquals(1, poll.getCandidates().size)
    }

    @Test
    fun `모임장이 아닌 회원의 요청은 Poll 잠금 조회 전에 거부한다`() {
        assertThrows<MeetingHostRequiredException> {
            service.createCandidate(10L, 2L, ScheduleCandidateRequest.Create(firstDate))
        }

        verify(polls, never()).findByMeetingIdForUpdate(any<Long>())
    }

    @Test
    fun `마감된 투표에는 새 후보를 추가할 수 없다`() {
        poll.close()

        assertThrows<SchedulePollClosedException> {
            service.createCandidate(10L, 1L, ScheduleCandidateRequest.Create(firstDate))
        }
        verify(candidates, never()).save(any())
    }
}
