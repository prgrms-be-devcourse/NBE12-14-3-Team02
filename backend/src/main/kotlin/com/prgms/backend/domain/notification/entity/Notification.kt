package com.prgms.backend.domain.notification.entity

import com.prgms.backend.domain.notification.ENUM.NotificationType
import com.prgms.backend.global.entity.BaseCreatedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "notifications")
class Notification(
    receiverId: Long,
    meetingId: Long?,
    type: NotificationType,
    title: String,
    content: String?,
    redirectUrl: String?,
) : BaseCreatedEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @Column(name = "receiver_id", nullable = false)
    var receiverId: Long = receiverId
        protected set

    @Column(name = "meeting_id")
    var meetingId: Long? = meetingId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: NotificationType = type
        protected set

    @Column(nullable = false, length = 100)
    var title: String = title
        protected set

    @Column(length = 500)
    var content: String? = content
        protected set

    @Column(name = "redirect_url")
    var redirectUrl: String? = redirectUrl
        protected set

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false
        protected set

    fun markRead() {
        isRead = true
    }
}
