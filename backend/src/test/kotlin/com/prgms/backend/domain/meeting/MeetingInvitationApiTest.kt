package com.prgms.backend.domain.meeting

import com.prgms.backend.domain.meeting.controller.MeetingInvitationController
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingInvitation
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingInvitationRepository
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.meeting.service.MeetingInvitationService
import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.global.exception.GlobalExceptionHandler
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.Optional

internal class MeetingInvitationApiTest {
    private lateinit var members: MeetingMemberRepository
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val meetings = mock<MeetingRepository>()
        val invitations =
            mock<MeetingInvitationRepository>()
        members = mock<MeetingMemberRepository>()
        val users = mock<UserRepository>()
        val service =
            MeetingInvitationService(
                meetingRepository = meetings,
                meetingInvitationRepository = invitations,
                meetingMemberRepository = members,
                userRepository = users,
            )
        mvc =
            MockMvcBuilders
                .standaloneSetup(MeetingInvitationController(service))
                .setControllerAdvice(GlobalExceptionHandler())
                .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
                .build()

        val user = User("member@test.com", "회원", "password")
        ReflectionTestUtils.setField(user, "id", 2L)
        val meeting = Meeting(user, "초대 테스트", "")
        ReflectionTestUtils.setField(meeting, "id", 10L)
        val invitation =
            MeetingInvitation(
                meeting,
                "invite-code",
                LocalDateTime.now().plusDays(1),
            )
        whenever(invitations.findMeetingIdByInviteCode("invite-code")).thenReturn(10L)
        whenever(meetings.findByIdAndDeletedAtIsNullForUpdate(10L))
            .thenReturn(meeting)
        whenever(invitations.findByInviteCode("invite-code"))
            .thenReturn(invitation)
        whenever(users.findById(2L)).thenReturn(Optional.of(user))
        whenever(members.findByMeetingIdAndUserIdForUpdate(10L, 2L))
            .thenReturn(null)
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken(
                SecurityUser(2L),
                null,
                emptyList(),
            ),
        )
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @ParameterizedTest
    @ValueSource(strings = ["uk_meeting_member_meeting_user", "meeting_members.uk_meeting_member_meeting_user" ])
    fun `저장 중 모임원 중복 UNIQUE 위반이 발생하면 HTTP 409로 응답한다`(constraintName: String) {
        failOnSave(constraintViolation(constraintName))

        mvc
            .perform(MockMvcRequestBuilders.post("/api/invitations/invite-code/join"))
            .andExpect(MockMvcResultMatchers.status().isConflict())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(409))
            .andExpect(
                MockMvcResultMatchers.jsonPath("$.message").value(
                    "이미 모임에 참여 중인 사용자입니다. meetingId = 10, userId = 2",
                ),
            )
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = [
            "uk_users_email",
            "fk_meeting_member_user",
            "uk_meeting_member_meeting_user_other",
            "other_table.uk_meeting_member_meeting_user",
        ],
    )
    fun `다른 UNIQUE·외래 키·식별 불가 오류는 중복 참여 409로 변환하지 않는다`(constraintName: String?) {
        failOnSave(constraintViolation(constraintName))

        mvc
            .perform(MockMvcRequestBuilders.post("/api/invitations/invite-code/join"))
            .andExpect(MockMvcResultMatchers.status().isInternalServerError())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(500))
    }

    @Test
    fun `메시지에 제약 이름만 있는 일반 무결성 오류는 409로 변환하지 않는다`() {
        failOnSave(DataIntegrityViolationException("uk_meeting_member_meeting_user"))

        mvc
            .perform(MockMvcRequestBuilders.post("/api/invitations/invite-code/join"))
            .andExpect(MockMvcResultMatchers.status().isInternalServerError())
    }

    private fun failOnSave(exception: DataIntegrityViolationException) {
        whenever(
            members.saveAndFlush(
                any<MeetingMember>(),
            ),
        ).thenThrow(exception)
    }

    private fun constraintViolation(constraintName: String?): DataIntegrityViolationException =
        DataIntegrityViolationException(
            "저장 실패",
            ConstraintViolationException("제약 위반", SQLException("제약 위반"), constraintName),
        )
}
