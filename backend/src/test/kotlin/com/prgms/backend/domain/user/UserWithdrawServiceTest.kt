package com.prgms.backend.domain.user

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.enums.MeetingStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.meeting.service.MeetingMemberService
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.service.UserWithdrawService
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberHasUnsettledExpenseException
import com.prgms.backend.global.exception.custom.user.UserNotFoundException
import com.prgms.backend.global.exception.custom.user.UserWithdrawActivateHostMeetingException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.util.Optional

internal class UserWithdrawServiceTest {
    private lateinit var userRepository: UserRepository
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var meetingMemberService: MeetingMemberService
    private lateinit var meetingMemberRepository: MeetingMemberRepository

    private lateinit var userWithdrawService: UserWithdrawService

    private lateinit var user: User
    private lateinit var otherHost: User

    @BeforeEach
    fun setUp() {
        userRepository = mock()
        meetingRepository = mock()
        meetingMemberService = mock()
        meetingMemberRepository = mock()

        userWithdrawService =
            UserWithdrawService(
                userRepository = userRepository,
                meetingRepository = meetingRepository,
                meetingMemberService = meetingMemberService,
                meetingMemberRepository = meetingMemberRepository,
            )

        user = createUser(1L, "user@test.com", "탈퇴회원")
        user.updateRefreshToken("refresh-token")
        otherHost = createUser(2L, "host@test.com", "모임장")

        whenever(userRepository.findById(1L))
            .thenReturn(Optional.of(user))
    }

    @Test
    fun `참여 중인 모임을 모두 탈퇴하고, 호스트인 완료 모임을 보존한 채 회원을 탈퇴 처리한다`() {
        // given
        val hostMeeting = createMeeting(10L, user)
        hostMeeting.complete()
        val memberMeeting = createMeeting(20L, otherHost)

        givenNoActiveHostMeeting()

        whenever(
            meetingMemberRepository.findAllByUserIdAndStatusAndMeetingDeletedAtIsNull(
                1L,
                MeetingMemberStatus.JOINED,
            ),
        ).thenReturn(
            listOf(
                MeetingMember(hostMeeting, user),
                MeetingMember(memberMeeting, user),
            ),
        )

        whenever(meetingRepository.findByHostIdAndDeletedAtIsNull(1L))
            .thenReturn(listOf(hostMeeting))

        // when
        userWithdrawService.withdraw(1L)

        // then
        verify(meetingMemberService).leaveMeeting(10L, 1L)
        verify(meetingMemberService).leaveMeeting(20L, 1L)

        assertNull(hostMeeting.deletedAt)
        assertEquals(MeetingStatus.COMPLETED, hostMeeting.status)
        assertNull(memberMeeting.deletedAt)

        assertEquals(UserStatus.WITHDRAWN, user.status)
        assertNotNull(user.deletedAt)
        assertNull(user.refreshToken)
    }

    @Test
    fun `회원 탈퇴 시 현재 참여 중인 모임이 없어도 내가 호스트였던 완료 모임은 보존한다`() {
        // given
        val hostMeeting = createMeeting(10L, user)
        hostMeeting.complete()

        givenNoActiveHostMeeting()

        // 현재 JOINED 상태인 모임은 없다.
        whenever(
            meetingMemberRepository.findAllByUserIdAndStatusAndMeetingDeletedAtIsNull(
                1L,
                MeetingMemberStatus.JOINED,
            ),
        ).thenReturn(emptyList())

        // 과거에 호스트였던 완료 모임은 존재한다.
        whenever(meetingRepository.findByHostIdAndDeletedAtIsNull(1L))
            .thenReturn(listOf(hostMeeting))

        // when
        userWithdrawService.withdraw(1L)

        // then: 현재 참여 중인 모임이 없으므로 모임 탈퇴 호출도 없다.
        verify(meetingMemberService, never()).leaveMeeting(any(), any())

        // 과거 완료 모임은 그대로 유지한다.
        assertNull(hostMeeting.deletedAt)
        assertEquals(MeetingStatus.COMPLETED, hostMeeting.status)

        // 계정 탈퇴는 정상적으로 완료한다.
        assertEquals(UserStatus.WITHDRAWN, user.status)
        assertNotNull(user.deletedAt)
        assertNull(user.refreshToken)
    }

    @Test
    fun `참여 중인 모임이 없어도 회원 탈퇴할 수 있다`() {
        // given
        givenNoActiveHostMeeting()

        whenever(
            meetingMemberRepository.findAllByUserIdAndStatusAndMeetingDeletedAtIsNull(
                1L,
                MeetingMemberStatus.JOINED,
            ),
        ).thenReturn(emptyList())

        // when
        userWithdrawService.withdraw(1L)

        // then
        verify(meetingMemberService, never()).leaveMeeting(any(), any())
        assertEquals(UserStatus.WITHDRAWN, user.status)
        assertNotNull(user.deletedAt)
        assertNull(user.refreshToken)
    }

    @Test
    fun `존재하지 않는 회원은 탈퇴할 수 없다`() {
        // given
        whenever(userRepository.findById(99L))
            .thenReturn(Optional.empty())

        // when & then
        assertThrows<UserNotFoundException> {
            userWithdrawService.withdraw(99L)
        }
    }

    @Test
    fun `진행 중인 모임의 모임장은 회원 탈퇴할 수 없다`() {
        // given
        whenever(
            meetingRepository.existsByHostIdAndStatusAndDeletedAtIsNull(
                1L,
                MeetingStatus.ACTIVE,
            ),
        ).thenReturn(true)

        // when & then
        assertThrows<UserWithdrawActivateHostMeetingException> {
            userWithdrawService.withdraw(1L)
        }

        verify(meetingMemberService, never()).leaveMeeting(any(), any())
        assertEquals(UserStatus.ACTIVE, user.status)
        assertNull(user.deletedAt)
    }

    @Test
    fun `미정산 지출이 있는 모임이 있으면 회원 탈퇴할 수 없다`() {
        // given
        val memberMeeting = createMeeting(20L, otherHost)

        givenNoActiveHostMeeting()

        whenever(
            meetingMemberRepository.findAllByUserIdAndStatusAndMeetingDeletedAtIsNull(
                1L,
                MeetingMemberStatus.JOINED,
            ),
        ).thenReturn(listOf(MeetingMember(memberMeeting, user)))

        whenever(meetingMemberService.leaveMeeting(20L, 1L))
            .thenThrow(MeetingMemberHasUnsettledExpenseException(20L, 1L))

        // when & then
        assertThrows<MeetingMemberHasUnsettledExpenseException> {
            userWithdrawService.withdraw(1L)
        }

        assertEquals(UserStatus.ACTIVE, user.status)
        assertNull(user.deletedAt)
        assertEquals("refresh-token", user.refreshToken)
    }

    private fun givenNoActiveHostMeeting() {
        whenever(
            meetingRepository.existsByHostIdAndStatusAndDeletedAtIsNull(
                1L,
                MeetingStatus.ACTIVE,
            ),
        ).thenReturn(false)
    }

    private fun createUser(
        id: Long,
        email: String,
        nickname: String,
    ): User {
        val user = User(email, nickname, "password")
        ReflectionTestUtils.setField(user, "id", id)
        return user
    }

    private fun createMeeting(
        id: Long,
        host: User,
    ): Meeting {
        val meeting = Meeting(host, "모임", "모임 설명")
        ReflectionTestUtils.setField(meeting, "id", id)
        return meeting
    }
}
