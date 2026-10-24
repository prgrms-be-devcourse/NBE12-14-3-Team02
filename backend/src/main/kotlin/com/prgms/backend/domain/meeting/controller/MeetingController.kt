package com.prgms.backend.domain.meeting.controller

import com.prgms.backend.domain.meeting.dto.request.MeetingCreateRequest
import com.prgms.backend.domain.meeting.dto.request.MeetingUpdateRequest
import com.prgms.backend.domain.meeting.dto.response.MeetingResponse
import com.prgms.backend.domain.meeting.service.MeetingService
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/meetings")
class MeetingController(
    private val meetingService: MeetingService,
) {
    // 모임 생성
    @PostMapping
    fun createMeeting(
        @AuthenticationPrincipal securityUser: SecurityUser,
        @Valid @RequestBody request: MeetingCreateRequest,
    ): ResponseEntity<ApiResponse<MeetingResponse>> {
        val response: MeetingResponse = meetingService.createMeeting(securityUser.id, request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(201, response))
    }

    // 모임 상세 조회
    @GetMapping("/{meetingId}")
    fun getMeeting(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<MeetingResponse>> {
        val response: MeetingResponse = meetingService.getMeeting(meetingId, securityUser.id)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }

    // 모임 목록 조회
    @GetMapping
    fun getMyMeetings(
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<List<MeetingResponse>>> {
        val responses: List<MeetingResponse> = meetingService.getMyMeetings(securityUser.id)
        return ResponseEntity.ok(ApiResponse.success(200, responses))
    }

    // 모임 수정
    @PutMapping("/{meetingId}")
    fun updateMeeting(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
        @Valid @RequestBody request: MeetingUpdateRequest,
    ): ResponseEntity<ApiResponse<MeetingResponse>> {
        val response: MeetingResponse = meetingService.updateMeeting(meetingId, securityUser.id, request)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }

    // 모임 종료
    @PatchMapping("/{meetingId}/complete")
    fun completeMeeting(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<MeetingResponse>> {
        val response: MeetingResponse = meetingService.completeMeeting(meetingId, securityUser.id)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }
}
