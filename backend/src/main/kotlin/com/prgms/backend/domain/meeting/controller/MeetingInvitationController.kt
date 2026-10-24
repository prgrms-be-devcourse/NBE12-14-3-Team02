package com.prgms.backend.domain.meeting.controller

import com.prgms.backend.domain.meeting.dto.response.MeetingInvitationDetailResponse
import com.prgms.backend.domain.meeting.dto.response.MeetingInvitationResponse
import com.prgms.backend.domain.meeting.dto.response.MeetingMemberResponse
import com.prgms.backend.domain.meeting.service.MeetingInvitationService
import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.global.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class MeetingInvitationController(
    private val meetingInvitationService: MeetingInvitationService,
) {
    // 초대 생성
    @PostMapping("/meetings/{meetingId}/invitations")
    fun createInvitation(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<MeetingInvitationResponse>> {
        val response: MeetingInvitationResponse = meetingInvitationService.createInvitation(meetingId, securityUser.id)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(201, response))
    }

    // 초대를 통한 모임 참여
    @PostMapping("/invitations/{inviteCode}/join")
    fun joinMeeting(
        @PathVariable inviteCode: String,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<MeetingMemberResponse>> {
        val response: MeetingMemberResponse = meetingInvitationService.joinMeeting(inviteCode, securityUser.id)
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }

    // 초대 코드 목록 조회
    @GetMapping("/meetings/{meetingId}/invitations")
    fun getInvitations(
        @PathVariable meetingId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<List<MeetingInvitationResponse>>> {
        val responses: List<MeetingInvitationResponse> =
            meetingInvitationService.getInvitations(
                meetingId,
                securityUser.id,
            )
        return ResponseEntity.ok(ApiResponse.success(200, responses))
    }

    // 초대받은 모임 정보 조회
    @GetMapping("/invitations/{inviteCode}")
    fun getInvitation(
        @PathVariable inviteCode: String,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<MeetingInvitationDetailResponse>> {
        val response: MeetingInvitationDetailResponse =
            meetingInvitationService.getInvitation(
                inviteCode,
                securityUser.id,
            )
        return ResponseEntity.ok(ApiResponse.success(200, response))
    }

    // 초대 코드 삭제
    @DeleteMapping("/meetings/{meetingId}/invitations/{invitationId}")
    fun deleteInvitation(
        @PathVariable meetingId: Long,
        @PathVariable invitationId: Long,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<Void>> {
        meetingInvitationService.deleteInvitation(meetingId, invitationId, securityUser.id)
        return ResponseEntity.ok(ApiResponse.success(200, null))
    }
}
