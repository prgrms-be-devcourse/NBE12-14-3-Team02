package com.prgms.backend.domain.schedule.poll.controller

import com.prgms.backend.domain.schedule.poll.dto.SchedulePollRequest
import com.prgms.backend.domain.schedule.poll.dto.SchedulePollResponse
import com.prgms.backend.domain.schedule.poll.dto.ScheduleResultResponse
import com.prgms.backend.domain.schedule.poll.service.SchedulePollService
import com.prgms.backend.domain.schedule.poll.service.ScheduleResultService
import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.global.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/meetings/{meetingId}/schedule-poll")
class SchedulePollController(
    private val schedulePollService: SchedulePollService,
    private val scheduleResultService: ScheduleResultService,
) {
    @PostMapping
    fun createPoll(
        @PathVariable meetingId: Long,
        @Valid @RequestBody request: SchedulePollRequest.Create,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<SchedulePollResponse.Created>> {
        val userId = securityUser.id

        val response = schedulePollService.createPoll(meetingId, userId, request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                ApiResponse.success(
                    201,
                    response,
                ),
            )
    }

    @GetMapping
    fun getPoll(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<SchedulePollResponse.Detail>> {
        val userId = securityUser.id
        val response =
            schedulePollService.getPoll(meetingId, userId)

        return ResponseEntity.ok(
            ApiResponse.success(
                200,
                response,
            ),
        )
    }

    @PatchMapping
    fun updateDeadline(
        @PathVariable meetingId: Long,
        @Valid @RequestBody request: SchedulePollRequest.UpdateDeadline,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<SchedulePollResponse.DeadlineUpdate>> {
        val userId = securityUser.id

        val response =
            schedulePollService.updateDeadline(meetingId, userId, request)

        return ResponseEntity.ok(
            ApiResponse.success(200, response),
        )
    }

    @GetMapping("/results")
    fun getResults(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<ScheduleResultResponse.Detail>> {
        val userId = securityUser.id

        val response =
            scheduleResultService.getResults(
                meetingId,
                userId,
            )

        return ResponseEntity.ok(
            ApiResponse.success(200, response),
        )
    }
}
