package com.prgms.backend.domain.meeting.controller

import com.prgms.backend.domain.meeting.dto.response.MeetingMemberResponse
import com.prgms.backend.domain.meeting.service.MeetingMemberService
import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.global.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/meetings")
class MeetingMemberController(
    private val meetingMemberService: MeetingMemberService,
) {
    // 현재 모임원 목록 조회
    @GetMapping("/{meetingId}/members")
    fun getMeetingMembers(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<List<MeetingMemberResponse>>> {
        val responses: List<MeetingMemberResponse> = meetingMemberService.getMeetingMembers(meetingId, securityUser.id)
        return ResponseEntity.ok(ApiResponse.success(200, responses))
    }

    // 현재 로그인한 사용자가 모임 탈퇴
    @DeleteMapping("/{meetingId}/members/me")
    fun leaveMeeting(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<MeetingMemberResponse>> {
        val response: MeetingMemberResponse = meetingMemberService.leaveMeeting(meetingId, securityUser.id)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }
}
