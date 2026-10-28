package com.prgms.backend.domain.notification.service

import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.notification.ENUM.NotificationType
import com.prgms.backend.domain.notification.dto.response.NotificationResponse
import com.prgms.backend.domain.notification.entity.Notification
import com.prgms.backend.domain.notification.repository.NotificationRepository
import com.prgms.backend.global.exception.custom.notification.NotificationAccessDeniedException
import com.prgms.backend.global.exception.custom.notification.NotificationNotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val meetingMemberRepository: MeetingMemberRepository,
) {
    @Transactional
    fun notifyContentClosed(meetingId: Long) {
        notifyAllJoin(
            meetingId,
            NotificationType.CONTENT_CLOSED,
            "콘텐츠 투표가 마감되었습니다.",
            "결과를 확인해 주세요.",
            "/meetings/$meetingId/content-poll",
        )
    }

    @Transactional
    fun notifyScheduleClosed(meetingId: Long) {
        notifyAllJoin(
            meetingId,
            NotificationType.SCHEDULE_CLOSED,
            "일정 투표가 마감되었습니다.",
            "결과를 확인해 주세요.",
            "/meetings/$meetingId/schedule-poll",
        )
    }

    @Transactional
    fun notifySettlementClosed(meetingId: Long) {
        notifyAllJoin(
            meetingId,
            NotificationType.SETTLEMENT_CLOSED,
            "정산이 마감되었습니다.",
            "정산 내역을 확인해 주세요.",
            "/meetings/$meetingId/settlement-poll",
        )
    }

    @Transactional(readOnly = true)
    fun getMyNotifications(userId: Long): List<NotificationResponse> =
        notificationRepository
            .findByReceiverIdOrderByCreatedAtDesc(userId)
            .map(NotificationResponse::from)

    @Transactional
    fun markRead(
        notificationId: Long,
        userId: Long,
    ): NotificationResponse {
        val notification =
            notificationRepository.findByIdOrNull(notificationId)
                ?: throw NotificationNotFoundException(notificationId)
        if (notification.receiverId != userId) {
            throw NotificationAccessDeniedException(notificationId, userId)
        }
        notification.markRead()
        return NotificationResponse.from(notification)
    }

    // 미팅 멤버들에게 모두 알림 보냄
    private fun notifyAllJoin(
        meetingId: Long,
        type: NotificationType,
        title: String,
        content: String,
        redirectUrl: String,
    ) {
        val members: List<MeetingMember> =
            meetingMemberRepository.findAllByMeetingIdAndStatus(meetingId, MeetingMemberStatus.JOINED)
        for (member in members) {
            notificationRepository.save(
                Notification(
                    member.user.id,
                    meetingId,
                    type,
                    title,
                    content,
                    redirectUrl,
                ),
            )
        }
    }
}
