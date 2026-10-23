package com.prgms.backend.domain.notification.dto.response

import com.fasterxml.jackson.annotation.JsonProperty
import com.prgms.backend.domain.notification.ENUM.NotificationType
import com.prgms.backend.domain.notification.entity.Notification
import java.time.LocalDateTime

data class NotificationResponse(
    val id: Long,
    val meetingId: Long?,
    val type: NotificationType,
    val title: String,
    val content: String?,
    val redirectUrl: String?,
    // Kotlin 기본 규칙으로는 "read"로 직렬화되므로 기존 응답 필드명을 명시한다.
    @get:JsonProperty("isRead")
    val isRead: Boolean,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(notification: Notification): NotificationResponse {
            check(notification.id != 0L) { "저장된 알림만 응답으로 변환할 수 있습니다." }
            return NotificationResponse(
                id = notification.id,
                meetingId = notification.meetingId,
                type = notification.type,
                title = notification.title,
                content = notification.content,
                redirectUrl = notification.redirectUrl,
                isRead = notification.isRead,
                createdAt = notification.createdAt,
            )
        }
    }
}
