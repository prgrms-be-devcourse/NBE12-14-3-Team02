package com.prgms.backend.domain.content

import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.integration.ContentPollClosedEvent
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.content.service.ContentPollCloseService
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.meeting
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.user
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

class ContentPollCloseServiceTest {
    private lateinit var contentPollRepository: ContentPollRepository
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: ContentPollCloseService

    private lateinit var meeting: Meeting
    private lateinit var poll: ContentPoll

    @BeforeEach
    fun setUp() {
        contentPollRepository = mock()
        meetingRepository = mock()
        eventPublisher = mock()
        service = ContentPollCloseService(contentPollRepository, meetingRepository, eventPublisher)

        meeting = meeting(MEETING_ID, user(1L, "host"))
        poll = poll(LocalDateTime.now().minusMinutes(1))

        whenever(contentPollRepository.findMeetingIdByPollId(POLL_ID)).thenReturn(MEETING_ID)
        whenever(meetingRepository.findByIdAndDeletedAtIsNullForShare(MEETING_ID)).thenReturn(meeting)
        whenever(contentPollRepository.findByIdForUpdate(POLL_ID)).thenReturn(poll)
    }

    private fun poll(deadline: LocalDateTime): ContentPoll {
        val contentPoll = ContentPoll(MEETING_ID, deadline)
        ReflectionTestUtils.setField(contentPoll, "id", POLL_ID)
        return contentPoll
    }

    @Test
    fun `마감기한이 지난 OPEN 투표판은 닫고 마감 이벤트를 발행`() {
        // when
        service.closeOneIfExpired(POLL_ID)

        // then
        assertEquals(ContentPollStatus.CLOSED, poll.status)
        verify(eventPublisher).publishEvent(ContentPollClosedEvent(MEETING_ID, POLL_ID))
    }

    @Test
    fun `종료된 모임의 투표판은 닫지 않음`() {
        // given
        meeting.complete()

        // when
        service.closeOneIfExpired(POLL_ID)

        // then
        assertEquals(ContentPollStatus.OPEN, poll.status)
        verify(contentPollRepository, never()).findByIdForUpdate(anyOrNull())
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `이미 닫힌 투표판은 이벤트를 다시 발행하지 않음`() {
        // given
        poll.close()

        // when
        service.closeOneIfExpired(POLL_ID)

        // then
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `락 획득 후 마감기한이 연장되었으면 닫지 않음`() {
        // given
        poll.changeDeadLine(LocalDateTime.now().plusDays(1))

        // when
        service.closeOneIfExpired(POLL_ID)

        // then
        assertEquals(ContentPollStatus.OPEN, poll.status)
        verifyNoInteractions(eventPublisher)
    }

    private companion object {
        const val MEETING_ID = 10L
        const val POLL_ID = 100L
    }
}
