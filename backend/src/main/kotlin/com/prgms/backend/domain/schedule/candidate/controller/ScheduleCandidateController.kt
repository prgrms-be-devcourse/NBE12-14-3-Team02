package com.prgms.backend.domain.schedule.candidate.controller

import com.prgms.backend.domain.schedule.candidate.dto.ScheduleCandidateRequest
import com.prgms.backend.domain.schedule.candidate.dto.ScheduleCandidateResponse
import com.prgms.backend.domain.schedule.candidate.service.ScheduleCandidateService
import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.global.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/meetings/{meetingId}/schedule-poll/candidates")
class ScheduleCandidateController(
    private val scheduleCandidateService: ScheduleCandidateService,
) {
    @PostMapping
    fun candidateCreate(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
        @Valid @RequestBody request: ScheduleCandidateRequest.Create,
    ): ResponseEntity<ApiResponse<ScheduleCandidateResponse.Summary>> {
        val userId = securityUser.id

        val response = scheduleCandidateService.createCandidate(meetingId, userId, request)

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                ApiResponse.success(
                    201,
                    response,
                ),
            )
    }

    @PatchMapping("/{candidateId}")
    fun update(
        @PathVariable meetingId: Long,
        @PathVariable candidateId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
        @Valid @RequestBody request: ScheduleCandidateRequest.Update,
    ): ResponseEntity<ApiResponse<ScheduleCandidateResponse.Summary>> {
        val userId = securityUser.id
        val response = scheduleCandidateService.update(meetingId, candidateId, userId, request)

        return ResponseEntity
            .status(HttpStatus.OK)
            .body(
                ApiResponse.success(
                    200,
                    response,
                ),
            )
    }

    @DeleteMapping("/{candidateId}")
    fun delete(
        @PathVariable meetingId: Long,
        @PathVariable candidateId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<Void> {
        val userId = securityUser.id

        scheduleCandidateService.delete(meetingId, candidateId, userId)

        return ResponseEntity.noContent().build()
    }
}
