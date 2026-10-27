package com.prgms.backend.domain.schedule.poll.service

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.poll.dto.SchedulePollRequest
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.candidate
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.meeting
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.member
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.poll
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.user
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.vote
import com.prgms.backend.global.exception.custom.meeting.MeetingHostRequiredException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberAlreadyLeftException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotActiveException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollAlreadyExistsException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollClosedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDate
import java.time.LocalDateTime

class SchedulePollServiceTest {
    private val polls = mock<SchedulePollRepository>()
    private val meetings = mock<MeetingRepository>()
    private val members = mock<MeetingMemberRepository>()
    private val service = SchedulePollService(polls, meetings, members)

    private lateinit var meeting: Meeting
    private lateinit var poll: SchedulePoll
    private lateinit var deadline: LocalDateTime

    @BeforeEach
    fun setUp() {
        meeting = meeting(10L, user(1L, "host"))
        deadline = LocalDateTime.now().plusDays(3)
        poll = poll(20L, meeting, deadline)
        whenever(meetings.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(meeting)
        whenever(meetings.findByIdAndDeletedAtIsNullForShare(10L)).thenReturn(meeting)
    }

    @Test
    fun `모임장이 일정 투표를 만들면 OPEN 상태와 마감 시간이 저장된다`() {
        whenever(polls.save(any<SchedulePoll>())).thenAnswer { invocation ->
            val saved = invocation.getArgument<SchedulePoll>(0)
            ReflectionTestUtils.setField(saved, "id", 21L)
            saved
        }

        val response = service.createPoll(10L, 1L, SchedulePollRequest.Create(deadline))

        assertEquals(21L, response.id)
        assertEquals(10L, response.meetingId)
        assertEquals(deadline, response.deadline)
        assertEquals(SchedulePollStatus.OPEN, response.status)
        verify(polls).save(any<SchedulePoll>())
    }

    @Test
    fun `이미 일정 투표가 있는 모임에는 두 번째 투표를 만들지 않는다`() {
        whenever(polls.existsByMeetingId(10L)).thenReturn(true)

        assertThrows<SchedulePollAlreadyExistsException> {
            service.createPoll(
                10L,
                1L,
                SchedulePollRequest.Create(deadline),
            )
        }

        verify(polls, never()).save(any())
    }

    @Test
    fun `모임장이 아닌 회원은 일정 투표를 만들 수 없다`() {
        assertThrows<MeetingHostRequiredException> { service.createPoll(10L, 2L, SchedulePollRequest.Create(deadline)) }

        verifyNoInteractions(polls)
    }

    @Test
    fun `종료된 모임에는 일정 투표를 새로 만들지 않는다`() {
        meeting.complete()

        assertThrows<MeetingNotActiveException> { service.createPoll(10L, 1L, SchedulePollRequest.Create(deadline)) }
        verifyNoInteractions(polls)
    }

    @Test
    fun `참여 중인 회원은 일정 투표와 등록된 후보를 함께 조회한다`() {
        val member = member(30L, meeting, user(2L, "member"))
        val candidateDate = LocalDate.now().plusDays(1)
        candidate(40L, poll, candidateDate)
        whenever(meetings.findByIdAndDeletedAtIsNull(10L)).thenReturn(meeting)
        whenever(members.findByMeetingIdAndUserId(10L, 2L)).thenReturn(member)
        whenever(polls.findByMeetingIdWithCandidates(10L)).thenReturn(poll)

        val response = service.getPoll(10L, 2L)

        assertEquals(20L, response.id)
        assertEquals(1, response.candidates.size)
        assertEquals(candidateDate, response.candidates.first().candidateDate)
    }

    @Test
    fun `모임에서 탈퇴한 회원은 일정 투표를 조회할 수 없다`() {
        val leftMember = member(30L, meeting, user(2L, "member"))
        leftMember.leave()
        whenever(meetings.findByIdAndDeletedAtIsNull(10L)).thenReturn(meeting)
        whenever(members.findByMeetingIdAndUserId(10L, 2L)).thenReturn(leftMember)

        assertThrows<MeetingMemberAlreadyLeftException> { service.getPoll(10L, 2L) }
        verify(polls, never()).findByMeetingIdWithCandidates(10L)
    }

    @Test
    fun `열린 투표의 마감 시간을 바꾸면 새 시간이 반영된다`() {
        val changed = deadline.plusDays(1)
        whenever(polls.findByMeetingIdForUpdate(10L)).thenReturn(poll)

        val response =
            service.updateDeadline(
                10L,
                1L,
                SchedulePollRequest.UpdateDeadline(changed),
            )

        assertEquals(changed, response.deadline)
        assertEquals(SchedulePollStatus.OPEN, response.status)
    }

    @Test
    fun `마감된 투표는 마감 시간을 변경할 수 없다`() {
        poll.close()
        whenever(polls.findByMeetingIdForUpdate(10L)).thenReturn(poll)

        assertThrows<SchedulePollClosedException> {
            service.updateDeadline(
                10L,
                1L,
                SchedulePollRequest.UpdateDeadline(deadline.plusDays(1)),
            )
        }
        assertEquals(deadline, poll.deadline)
    }
}
