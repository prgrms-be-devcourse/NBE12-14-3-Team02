package com.prgms.backend.domain.meeting

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingInvitation
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingInvitationRepository
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.meeting.service.MeetingInvitationService
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.global.exception.custom.meeting.AlreadyMeetingMemberException
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException
import com.prgms.backend.global.exception.custom.meeting.MeetingInvitationNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotActiveException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import com.prgms.backend.support.AuditFields.filled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

internal class MeetingInvitationServiceTest {
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var meetingInvitationRepository: MeetingInvitationRepository
    private lateinit var meetingMemberRepository: MeetingMemberRepository
    private lateinit var userRepository: UserRepository

    private lateinit var meetingInvitationService: MeetingInvitationService

    @BeforeEach
    fun setUp() {
        meetingRepository = mock<MeetingRepository>()
        meetingInvitationRepository =
            mock<MeetingInvitationRepository>()
        meetingMemberRepository =
            mock<MeetingMemberRepository>()
        userRepository = mock<UserRepository>()

        meetingInvitationService =
            MeetingInvitationService(
                meetingRepository = meetingRepository,
                meetingInvitationRepository = meetingInvitationRepository,
                meetingMemberRepository = meetingMemberRepository,
                userRepository = userRepository,
            )
    }

    @Test
    fun `모임장은 초대 코드를 생성할 수 있다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val meeting = createMeeting(10L, host, "제주도 여행")

        whenever(
            meetingRepository
                .findByIdAndDeletedAtIsNullForShare(10L),
        ).thenReturn(meeting)

        whenever(
            meetingInvitationRepository
                .save(any<MeetingInvitation>()),
        ).thenAnswer { invocation ->
            val invitation: MeetingInvitation = invocation.getArgument(0)
            ReflectionTestUtils.setField(invitation, "id", 100L)
            filled(invitation)
        }

        // when
        val response =
            meetingInvitationService.createInvitation(10L, 1L)

        // then
        assertEquals(100L, response.id)
        assertEquals(10L, response.meetingId)

        assertNotNull(response.inviteCode)
        assertFalse(response.inviteCode.isBlank())

        assertNotNull(response.expiresAt)

        verify(meetingInvitationRepository)
            .save(any<MeetingInvitation>())
    }

    @Test
    fun `존재하지 않는 모임에는 초대를 생성할 수 없다`() {
        // given
        whenever(
            meetingRepository
                .findByIdAndDeletedAtIsNullForShare(10L),
        ).thenReturn(null)

        // when & then
        assertThrows<MeetingNotFoundException> {
            meetingInvitationService.createInvitation(10L, 1L)
        }
    }

    @Test
    fun `초대를 통해 처음 모임에 참여할 수 있다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val user = createUser(2L, "user@test.com", "회원")

        val meeting = createMeeting(10L, host, "제주도 여행")

        val invitation =
            createInvitation(
                100L,
                meeting,
                "invite-code",
                LocalDateTime.now().plusDays(1),
            )

        whenever(
            meetingInvitationRepository
                .findMeetingIdByInviteCode("invite-code"),
        ).thenReturn(10L)

        whenever(
            meetingInvitationRepository
                .findByInviteCode("invite-code"),
        ).thenReturn(invitation)

        whenever(
            meetingRepository
                .findByIdAndDeletedAtIsNullForUpdate(10L),
        ).thenReturn(meeting)

        whenever(userRepository.findById(2L))
            .thenReturn(Optional.of(user))

        whenever(
            meetingMemberRepository
                .findByMeetingIdAndUserIdForUpdate(10L, 2L),
        ).thenReturn(null)

        whenever(
            meetingMemberRepository
                .saveAndFlush(any<MeetingMember>()),
        ).thenAnswer { invocation ->
            val member: MeetingMember = invocation.getArgument(0)
            ReflectionTestUtils.setField(member, "id", 200L)
            member
        }

        // when
        val response =
            meetingInvitationService.joinMeeting("invite-code", 2L)

        // then
        assertEquals(200L, response.id)
        assertEquals(10L, response.meetingId)
        assertEquals(2L, response.userId)
        assertEquals(MeetingMemberStatus.JOINED, response.status)

        // 가입 여부 판단에 사용할 조회가 호출됐는지 확인
        verify(meetingMemberRepository)
            .findByMeetingIdAndUserIdForUpdate(10L, 2L)

        // 신규 가입이므로 새 모임원을 저장
        verify(meetingMemberRepository)
            .saveAndFlush(any<MeetingMember>())
    }

    @Test
    fun `탈퇴했던 회원은 초대를 통해 다시 가입할 수 있다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val user = createUser(2L, "user@test.com", "회원")

        val meeting = createMeeting(10L, host, "제주도 여행")

        val invitation =
            createInvitation(
                100L,
                meeting,
                "invite-code",
                LocalDateTime.now().plusDays(1),
            )

        val member =
            MeetingMember(meeting, user)

        ReflectionTestUtils.setField(member, "id", 200L)

        // 기존 회원이 탈퇴한 상태
        member.leave()

        whenever(
            meetingInvitationRepository
                .findMeetingIdByInviteCode("invite-code"),
        ).thenReturn(10L)

        whenever(
            meetingInvitationRepository
                .findByInviteCode("invite-code"),
        ).thenReturn(invitation)

        whenever(
            meetingRepository
                .findByIdAndDeletedAtIsNullForUpdate(10L),
        ).thenReturn(meeting)

        whenever(userRepository.findById(2L))
            .thenReturn(Optional.of(user))

        whenever(
            meetingMemberRepository
                .findByMeetingIdAndUserIdForUpdate(10L, 2L),
        ).thenReturn(member)

        // when
        val response =
            meetingInvitationService.joinMeeting("invite-code", 2L)

        // then
        assertEquals(MeetingMemberStatus.JOINED, response.status)

        assertEquals(200L, response.id)
        assertEquals(10L, response.meetingId)
        assertEquals(2L, response.userId)

        // 재가입하면 기존 탈퇴 시각 해제
        assertNull(response.leftAt)

        // 기존 MeetingMember를 재사용하므로 새로 저장X
        verify(meetingMemberRepository, never())
            .saveAndFlush(any<MeetingMember>())
    }

    @Test
    fun `이미 참여 중인 회원은 다시 가입할 수 없다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val user = createUser(2L, "user@test.com", "회원")

        val meeting = createMeeting(10L, host, "제주도 여행")

        val invitation =
            createInvitation(
                100L,
                meeting,
                "invite-code",
                LocalDateTime.now().plusDays(1),
            )

        val member =
            MeetingMember(meeting, user)

        whenever(
            meetingInvitationRepository
                .findMeetingIdByInviteCode("invite-code"),
        ).thenReturn(10L)

        whenever(
            meetingInvitationRepository
                .findByInviteCode("invite-code"),
        ).thenReturn(invitation)

        whenever(
            meetingRepository
                .findByIdAndDeletedAtIsNullForUpdate(10L),
        ).thenReturn(meeting)

        whenever(userRepository.findById(2L))
            .thenReturn(Optional.of(user))

        whenever(
            meetingMemberRepository
                .findByMeetingIdAndUserIdForUpdate(10L, 2L),
        ).thenReturn(member)

        // when & then
        val exception =
            assertThrows<AlreadyMeetingMemberException> {
                meetingInvitationService.joinMeeting("invite-code", 2L)
            }

        assertEquals(409, exception.code)

        // 중복 참여자는 새로 저장X
        verify(meetingMemberRepository, never())
            .saveAndFlush(any<MeetingMember>())
    }

    @Test
    fun `모임장은 초대 코드 목록을 조회할 수 있다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val meeting = createMeeting(10L, host, "제주도 여행")

        val invitation1 =
            createInvitation(
                100L,
                meeting,
                "invite-code-1",
                LocalDateTime.now().plusDays(1),
            )

        val invitation2 =
            createInvitation(
                200L,
                meeting,
                "invite-code-2",
                LocalDateTime.now().plusDays(2),
            )

        whenever(
            meetingRepository
                .findByIdAndDeletedAtIsNull(10L),
        ).thenReturn(meeting)

        whenever(
            meetingInvitationRepository
                .findAllByMeetingId(10L),
        ).thenReturn(
            listOf(invitation1, invitation2),
        )

        // when
        val responses =
            meetingInvitationService.getInvitations(10L, 1L)

        // then
        assertEquals(2, responses.size)

        assertEquals("invite-code-1", responses[0].inviteCode)

        assertEquals("invite-code-2", responses[1].inviteCode)

        assertEquals(10L, responses[0].meetingId)
    }

    @Test
    fun `유효한 초대 코드로 초대받은 모임 정보를 조회할 수 있다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val meeting = createMeeting(10L, host, "제주도 여행")

        val invitation =
            createInvitation(
                100L,
                meeting,
                "invite-code",
                LocalDateTime.now().plusDays(1),
            )

        whenever(
            meetingInvitationRepository
                .findByInviteCode("invite-code"),
        ).thenReturn(invitation)

        whenever(
            meetingMemberRepository
                .existsByMeetingIdAndUserIdAndStatus(10L, 2L, MeetingMemberStatus.JOINED),
        ).thenReturn(false)

        // when
        val response =
            meetingInvitationService.getInvitation("invite-code", 2L)

        // then
        assertEquals("invite-code", response.inviteCode)

        assertEquals(10L, response.meetingId)

        assertEquals("제주도 여행", response.meetingName)

        assertFalse(response.alreadyJoined)
    }

    @Test
    fun `이미 참여 중인 회원이 초대를 조회하면 참여 중으로 표시된다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val meeting = createMeeting(10L, host, "제주도 여행")

        val invitation =
            createInvitation(
                100L,
                meeting,
                "invite-code",
                LocalDateTime.now().plusDays(1),
            )

        whenever(
            meetingInvitationRepository
                .findByInviteCode("invite-code"),
        ).thenReturn(invitation)

        whenever(
            meetingMemberRepository
                .existsByMeetingIdAndUserIdAndStatus(10L, 2L, MeetingMemberStatus.JOINED),
        ).thenReturn(true)

        // when
        val response =
            meetingInvitationService.getInvitation("invite-code", 2L)

        // then
        assertEquals(10L, response.meetingId)

        assertEquals("제주도 여행", response.meetingName)

        assertTrue(response.alreadyJoined)
    }

    @Test
    fun `모임장은 초대 코드를 삭제할 수 있다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val meeting = createMeeting(10L, host, "제주도 여행")

        val invitation =
            createInvitation(
                100L,
                meeting,
                "invite-code",
                LocalDateTime.now().plusDays(1),
            )

        whenever(
            meetingRepository
                .findByIdAndDeletedAtIsNullForShare(10L),
        ).thenReturn(meeting)

        whenever(
            meetingInvitationRepository
                .findById(100L),
        ).thenReturn(Optional.of(invitation))

        // when
        meetingInvitationService.deleteInvitation(10L, 100L, 1L)

        // then
        verify(meetingInvitationRepository)
            .delete(invitation)
    }

    @Test
    fun `모임장이 아니면 초대 코드를 삭제할 수 없다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val meeting = createMeeting(10L, host, "제주도 여행")

        whenever(
            meetingRepository
                .findByIdAndDeletedAtIsNullForShare(10L),
        ).thenReturn(meeting)

        // when & then
        assertThrows<MeetingAccessDeniedException> {
            meetingInvitationService.deleteInvitation(10L, 100L, 2L)
        }

        verify(
            meetingInvitationRepository,
            never(),
        ).delete(any<MeetingInvitation>())
    }

    @Test
    fun `종료된 모임의 초대 코드는 삭제할 수 없다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val meeting = createMeeting(10L, host, "제주도 여행")

        meeting.complete()

        whenever(
            meetingRepository
                .findByIdAndDeletedAtIsNullForShare(10L),
        ).thenReturn(meeting)

        // when & then
        assertThrows<MeetingNotActiveException> {
            meetingInvitationService.deleteInvitation(10L, 100L, 1L)
        }

        verify(
            meetingInvitationRepository,
            never(),
        ).delete(any<MeetingInvitation>())
    }

    @Test
    fun `존재하지 않는 초대 코드는 삭제할 수 없다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val meeting = createMeeting(10L, host, "제주도 여행")

        whenever(
            meetingRepository
                .findByIdAndDeletedAtIsNullForShare(10L),
        ).thenReturn(meeting)

        whenever(
            meetingInvitationRepository
                .findById(100L),
        ).thenReturn(Optional.empty())

        // when & then
        assertThrows<MeetingInvitationNotFoundException> {
            meetingInvitationService.deleteInvitation(10L, 100L, 1L)
        }

        verify(
            meetingInvitationRepository,
            never(),
        ).delete(any<MeetingInvitation>())
    }

    @Test
    fun `다른 모임의 초대 코드는 삭제할 수 없다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")

        val meeting = createMeeting(10L, host, "제주도 여행")

        val otherMeeting = createMeeting(20L, host, "부산 여행")

        // 100번 초대는 10번 모임이 아니라
        // 20번 모임의 초대
        val invitation =
            createInvitation(
                100L,
                otherMeeting,
                "other-invite-code",
                LocalDateTime.now().plusDays(1),
            )

        whenever(
            meetingRepository
                .findByIdAndDeletedAtIsNullForShare(10L),
        ).thenReturn(meeting)

        whenever(
            meetingInvitationRepository
                .findById(100L),
        ).thenReturn(Optional.of(invitation))

        // when & then
        assertThrows<MeetingInvitationNotFoundException> {
            meetingInvitationService.deleteInvitation(10L, 100L, 1L)
        }

        verify(
            meetingInvitationRepository,
            never(),
        ).delete(any<MeetingInvitation>())
    }

    private fun createUser(
        id: Long,
        email: String,
        nickname: String,
    ): User =
        User(email, nickname, "password").also {
            ReflectionTestUtils.setField(it, "id", id)
        }

    private fun createMeeting(
        id: Long,
        host: User,
        name: String,
    ): Meeting =
        Meeting(host, name, "테스트 모임 설명").also {
            ReflectionTestUtils.setField(it, "id", id)
        }

    private fun createInvitation(
        id: Long,
        meeting: Meeting,
        inviteCode: String,
        expiresAt: LocalDateTime,
    ): MeetingInvitation =
        MeetingInvitation(meeting, inviteCode, expiresAt).also {
            ReflectionTestUtils.setField(it, "id", id)
            filled(it)
        }
}
