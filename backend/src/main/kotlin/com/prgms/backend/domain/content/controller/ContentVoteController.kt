package com.prgms.backend.domain.content.controller

import com.prgms.backend.domain.content.dto.request.ContentVoteRequest
import com.prgms.backend.domain.content.dto.response.ContentVoteResponse
import com.prgms.backend.domain.content.service.ContentVoteService
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
@RequestMapping("/api/meetings/{meetingId}/content-votes")
class ContentVoteController(
    private val contentVoteService: ContentVoteService,
) {
    @PutMapping
    fun upsert(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
        @Valid @RequestBody request: ContentVoteRequest.Submit,
    ): ResponseEntity<ApiResponse<ContentVoteResponse.Saved>> {
        val response = contentVoteService.upsert(meetingId, securityUser.id, request)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }
}
