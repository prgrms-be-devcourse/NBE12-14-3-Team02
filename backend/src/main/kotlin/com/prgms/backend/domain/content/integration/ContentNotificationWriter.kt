package com.prgms.backend.domain.content.integration

import com.prgms.backend.domain.notification.service.NotificationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class ContentNotificationWriter(
    private val notificationService: NotificationService,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun save(meetingId: Long) {
        notificationService.notifyContentClosed(meetingId)
    }
}
