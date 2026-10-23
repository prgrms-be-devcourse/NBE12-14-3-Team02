package com.prgms.backend.domain.notification.controller

import com.prgms.backend.domain.notification.dto.response.NotificationResponse
import com.prgms.backend.domain.notification.service.NotificationService
import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.global.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val notificationService: NotificationService,
) {
    @GetMapping
    fun getMine(
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<List<NotificationResponse>>> {
        val response = notificationService.getMyNotifications(securityUser.id)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }

    @PatchMapping("/{notificationId}/read")
    fun markRead(
        @PathVariable notificationId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<NotificationResponse>> {
        val response = notificationService.markRead(notificationId, securityUser.id)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }
}
