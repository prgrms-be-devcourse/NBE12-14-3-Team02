package com.prgms.backend.domain.content.controller

import com.prgms.backend.domain.content.dto.request.ContentPollRequest
import com.prgms.backend.domain.content.dto.response.ContentPollResponse
import com.prgms.backend.domain.content.dto.response.ContentPollResultsResponse
import com.prgms.backend.domain.content.enums.ContentPollResultSort
import com.prgms.backend.domain.content.service.ContentPollService
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/meetings/{meetingId}/content-poll")
class ContentPollController(
    private val contentPollService: ContentPollService,
) {
    @PostMapping
    fun create(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
        @Valid @RequestBody request: ContentPollRequest.Create,
    ): ResponseEntity<ApiResponse<ContentPollResponse.Created>> {
        val response = contentPollService.create(meetingId, securityUser.id, request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(201, response))
    }

    @GetMapping
    fun get(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<ContentPollResponse.Detail>> {
        val response = contentPollService.get(meetingId, securityUser.id)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }

    @PatchMapping
    fun updateDeadline(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
        @Valid @RequestBody request: ContentPollRequest.UpdateDeadline,
    ): ResponseEntity<ApiResponse<ContentPollResponse.DeadlineUpdate>> {
        val response = contentPollService.updateDeadline(meetingId, securityUser.id, request)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }

    @GetMapping("/results")
    fun getResults(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
        @RequestParam(defaultValue = "SCORE") sort: ContentPollResultSort,
    ): ResponseEntity<ApiResponse<ContentPollResultsResponse.Detail>> {
        val response = contentPollService.getResults(meetingId, securityUser.id, sort)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }

    @PostMapping("/confirm")
    fun confirm(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
        @Valid @RequestBody request: ContentPollRequest.Confirm,
    ): ResponseEntity<ApiResponse<ContentPollResponse.Confirmed>> {
        val response = contentPollService.confirm(meetingId, securityUser.id, request)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }
}
