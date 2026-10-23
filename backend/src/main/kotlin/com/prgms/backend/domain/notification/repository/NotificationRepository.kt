package com.prgms.backend.domain.notification.repository

import com.prgms.backend.domain.notification.entity.Notification
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationRepository : JpaRepository<Notification, Long> {
    fun findByReceiverIdOrderByCreatedAtDesc(receiverId: Long): List<Notification>
}
