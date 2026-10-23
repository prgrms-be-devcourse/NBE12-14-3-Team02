package com.prgms.backend.domain.content.controller

import com.prgms.backend.domain.content.dto.request.ContentCandidateRequest
import com.prgms.backend.domain.content.dto.response.ContentCandidateResponse
import com.prgms.backend.domain.content.service.ContentCandidateService
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
@RequestMapping("/api/meetings/{meetingId}/content-poll/candidates")
class ContentCandidateController(
    private val contentCandidateService: ContentCandidateService,
) {
    @PostMapping
    fun create(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
        @Valid @RequestBody request: ContentCandidateRequest.Create,
    ): ResponseEntity<ApiResponse<ContentCandidateResponse.Saved>> {
        val response = contentCandidateService.create(meetingId, securityUser.id, request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(201, response))
    }

    @PatchMapping("/{candidateId}")
    fun update(
        @PathVariable meetingId: Long,
        @PathVariable candidateId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
        @Valid @RequestBody request: ContentCandidateRequest.Update,
    ): ResponseEntity<ApiResponse<ContentCandidateResponse.Saved>> {
        val response = contentCandidateService.update(meetingId, candidateId, securityUser.id, request)
        return ResponseEntity
            .status(HttpStatus.OK)
            .body(ApiResponse.success(200, response))
    }

    @DeleteMapping("/{candidateId}")
    fun delete(
        @PathVariable meetingId: Long,
        @PathVariable candidateId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<Void> {
        contentCandidateService.delete(meetingId, candidateId, securityUser.id)
        return ResponseEntity.noContent().build()
    }
}
