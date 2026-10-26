package com.prgms.backend.domain.schedule.vote.controller

import com.prgms.backend.domain.schedule.vote.dto.ScheduleVoteRequest
import com.prgms.backend.domain.schedule.vote.dto.ScheduleVoteResponse
import com.prgms.backend.domain.schedule.vote.service.ScheduleVoteService
import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.global.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/meetings/{meetingId}/schedule-poll/candidates/{candidateId}/vote")
class ScheduleVoteController(
    private val scheduleVoteService: ScheduleVoteService,
) {
    @PutMapping
    fun upsert(
        @PathVariable meetingId: Long,
        @PathVariable candidateId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
        @Valid @RequestBody request: ScheduleVoteRequest.Submit,
    ): ResponseEntity<ApiResponse<ScheduleVoteResponse.Saved>> {
        val userId = securityUser.id

        val response =
            scheduleVoteService.submit(
                meetingId,
                candidateId,
                userId,
                request,
            )

        return ResponseEntity.ok(
            ApiResponse.success(200, response),
        )
    }
}
