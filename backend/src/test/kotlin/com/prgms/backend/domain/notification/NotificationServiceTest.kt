package com.prgms.backend.domain.notification

import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.notification.ENUM.NotificationType
import com.prgms.backend.domain.notification.entity.Notification
import com.prgms.backend.domain.notification.repository.NotificationRepository
import com.prgms.backend.domain.notification.service.NotificationService
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.meeting
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.member
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.user
import com.prgms.backend.global.exception.custom.notification.NotificationAccessDeniedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.util.Optional

class NotificationServiceTest {
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var meetingMemberRepository: MeetingMemberRepository
    private lateinit var service: NotificationService

    @BeforeEach
    fun setUp() {
        notificationRepository = mock()
        meetingMemberRepository = mock()
        service = NotificationService(notificationRepository, meetingMemberRepository)

        val meeting = meeting(MEETING_ID, user(1L, "host"))
        val host = member(30L, meeting, meeting.host)
        val guest = member(31L, meeting, user(2L, "guest"))
        whenever(meetingMemberRepository.findAllByMeetingIdAndStatus(MEETING_ID, MeetingMemberStatus.JOINED))
            .thenReturn(listOf(host, guest))
    }

    private fun savedNotifications(expectedCount: Int): List<Notification> {
        val captor = argumentCaptor<Notification>()
        verify(notificationRepository, times(expectedCount)).save(captor.capture())
        return captor.allValues
    }

    private fun notification(
        id: Long,
        receiverId: Long,
    ): Notification {
        val notification =
            Notification(
                receiverId,
                MEETING_ID,
                NotificationType.CONTENT_CLOSED,
                "제목",
                "내용",
                "/url",
            )
        ReflectionTestUtils.setField(notification, "id", id)
        return notification
    }

    @Test
    fun `콘텐츠 투표 마감 알림을 참여 중인 모든 멤버에게 저장`() {
        // when
        service.notifyContentClosed(MEETING_ID)

        // then
        val saved = savedNotifications(2)
        assertEquals(listOf(1L, 2L), saved.map { it.receiverId })
        val first = saved[0]
        assertEquals(NotificationType.CONTENT_CLOSED, first.type)
        assertEquals(MEETING_ID, first.meetingId)
        assertEquals("콘텐츠 투표가 마감되었습니다.", first.title)
        assertEquals("/meetings/10/content-poll", first.redirectUrl)
        assertFalse(first.isRead)
    }

    @Test
    fun `참여 중인 멤버가 없으면 알림을 저장하지 않음`() {
        // given
        whenever(meetingMemberRepository.findAllByMeetingIdAndStatus(MEETING_ID, MeetingMemberStatus.JOINED))
            .thenReturn(emptyList())

        // when
        service.notifyContentClosed(MEETING_ID)

        // then
        verify(notificationRepository, never()).save(any<Notification>())
    }

    @Test
    fun `다른 사람의 알림은 읽음 처리할 수 없고 읽음 상태도 바뀌지 않음`() {
        // given
        val notification = notification(5L, 1L)
        whenever(notificationRepository.findById(5L)).thenReturn(Optional.of(notification))

        // when / then
        assertThrows<NotificationAccessDeniedException> { service.markRead(5L, 2L) }
        assertFalse(notification.isRead)
    }

    private companion object {
        const val MEETING_ID = 10L
    }
}
