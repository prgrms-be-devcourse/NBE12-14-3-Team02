package com.prgms.backend.domain.meeting

import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.meeting.dto.request.MeetingCreateRequest
import com.prgms.backend.domain.meeting.dto.request.MeetingUpdateRequest
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.enums.MeetingStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.meeting.service.MeetingService
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.settlement.enums.SettlementStatus
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingPollNotCompletedException
import com.prgms.backend.global.exception.custom.meeting.MeetingSettlementNotCompletedException
import com.prgms.backend.global.exception.custom.user.UserNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.util.Optional

internal class MeetingServiceTest {
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var meetingMemberRepository: MeetingMemberRepository
    private lateinit var userRepository: UserRepository
    private lateinit var settlementRepository: SettlementRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var schedulePollRepository: SchedulePollRepository
    private lateinit var contentPollRepository: ContentPollRepository
    private lateinit var meetingService: MeetingService

    @BeforeEach
    fun setUp() {
        meetingRepository = mock()
        meetingMemberRepository = mock()
        userRepository = mock()
        settlementRepository = mock()
        expenseRepository = mock()
        schedulePollRepository = mock()
        contentPollRepository = mock()

        meetingService =
            MeetingService(
                meetingRepository = meetingRepository,
                meetingMemberRepository = meetingMemberRepository,
                userRepository = userRepository,
                settlementRepository = settlementRepository,
                expenseRepository = expenseRepository,
                schedulePollRepository = schedulePollRepository,
                contentPollRepository = contentPollRepository,
            )
    }

    @Test
    fun `모임을 생성하면 생성자는 모임장으로 등록된다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")
        val request = MeetingCreateRequest("제주도 여행", "제주도 여행 모임")

        whenever(userRepository.findById(1L)).thenReturn(Optional.of(host))
        whenever(meetingRepository.save(any<Meeting>())).thenAnswer { invocation ->
            val meeting: Meeting = invocation.getArgument(0)
            ReflectionTestUtils.setField(meeting, "id", 10L)
            meeting
        }
        whenever(meetingMemberRepository.countByMeetingIdAndStatus(10L, MeetingMemberStatus.JOINED)).thenReturn(1L)

        // when
        val response = meetingService.createMeeting(1L, request)

        // then
        assertEquals(10L, response.id)
        assertEquals(1L, response.hostId)
        assertEquals("제주도 여행", response.name)
        assertEquals(1L, response.participantCount)
        verify(meetingRepository).save(any<Meeting>())
        verify(meetingMemberRepository).save(any<MeetingMember>())
    }

    @Test
    fun `참여 중인 회원은 모임 상세 정보를 조회할 수 있다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")
        val meeting = Meeting(host, "제주도 여행", "제주도 여행 모임")
        ReflectionTestUtils.setField(meeting, "id", 10L)

        whenever(meetingRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(meeting)
        whenever(
            meetingMemberRepository.existsByMeetingIdAndUserIdAndStatus(10L, 2L, MeetingMemberStatus.JOINED),
        ).thenReturn(true)
        whenever(meetingMemberRepository.countByMeetingIdAndStatus(10L, MeetingMemberStatus.JOINED)).thenReturn(3L)

        // when
        val response = meetingService.getMeeting(10L, 2L)

        // then
        assertEquals(10L, response.id)
        assertEquals("제주도 여행", response.name)
        assertEquals(3L, response.participantCount)
    }

    @Test
    fun `존재하지 않는 회원은 모임을 생성할 수 없다`() {
        // given
        val request = MeetingCreateRequest("제주도 여행", "제주도 여행 모임")
        whenever(userRepository.findById(1L)).thenReturn(Optional.empty())

        // when & then
        assertThrows<UserNotFoundException> {
            meetingService.createMeeting(1L, request)
        }
    }

    @Test
    fun `참여 중인 모임 목록을 조회할 수 있다`() {
        // given
        val user = createUser(1L, "user@test.com", "회원")
        val meeting1 = createMeeting(10L, user, "여행 모임")
        val meeting2 = createMeeting(20L, user, "스터디 모임")
        val member1 = MeetingMember(meeting1, user)
        val member2 = MeetingMember(meeting2, user)

        whenever(userRepository.existsById(1L)).thenReturn(true)
        whenever(
            meetingMemberRepository.findAllByUserIdAndStatusAndMeetingDeletedAtIsNull(1L, MeetingMemberStatus.JOINED),
        ).thenReturn(listOf(member1, member2))

        // 모임별 참여자 수를 일괄 조회하는 현재 서비스에 맞춘 설정이다.
        whenever(
            meetingMemberRepository.countByMeetingIdsAndStatus(listOf(10L, 20L), MeetingMemberStatus.JOINED),
        ).thenReturn(listOf(arrayOf<Any>(10L, 2L), arrayOf<Any>(20L, 4L)))

        // 기존 Java 테스트의 단건 조회 설정도 유지한다.
        whenever(meetingMemberRepository.countByMeetingIdAndStatus(10L, MeetingMemberStatus.JOINED)).thenReturn(2L)
        whenever(meetingMemberRepository.countByMeetingIdAndStatus(20L, MeetingMemberStatus.JOINED)).thenReturn(4L)

        // when
        val responses = meetingService.getMyMeetings(1L)

        // then
        assertEquals(2, responses.size)
        assertEquals("여행 모임", responses[0].name)
        assertEquals("스터디 모임", responses[1].name)
        assertEquals(2L, responses[0].participantCount)
        assertEquals(4L, responses[1].participantCount)
    }

    @Test
    fun `참여 중인 모임이 없으면 빈 목록을 반환한다`() {
        // given
        whenever(userRepository.existsById(1L)).thenReturn(true)
        whenever(
            meetingMemberRepository.findAllByUserIdAndStatusAndMeetingDeletedAtIsNull(1L, MeetingMemberStatus.JOINED),
        ).thenReturn(emptyList())

        // when
        val responses = meetingService.getMyMeetings(1L)

        // then
        assertEquals(0, responses.size)
    }

    @Test
    fun `존재하지 않는 회원은 참여 중인 모임 목록을 조회할 수 없다`() {
        // given
        whenever(userRepository.existsById(1L)).thenReturn(false)

        // when & then
        assertThrows<UserNotFoundException> {
            meetingService.getMyMeetings(1L)
        }
    }

    @Test
    fun `모임장은 진행 중인 모임 정보를 수정할 수 있다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")
        val meeting = createMeeting(10L, host, "기존 모임")
        val request = MeetingUpdateRequest("수정된 모임", "수정된 설명")

        whenever(meetingRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(meeting)
        whenever(meetingMemberRepository.countByMeetingIdAndStatus(10L, MeetingMemberStatus.JOINED)).thenReturn(3L)

        // when
        val response = meetingService.updateMeeting(10L, 1L, request)

        // then
        assertEquals("수정된 모임", response.name)
        assertEquals("수정된 설명", response.description)
    }

    @Test
    fun `존재하지 않는 모임은 수정할 수 없다`() {
        // given
        val request = MeetingUpdateRequest("수정된 모임", "수정된 설명")
        whenever(meetingRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(null)

        // when & then
        assertThrows<MeetingNotFoundException> {
            meetingService.updateMeeting(10L, 1L, request)
        }
    }

    @Test
    fun `지출 내역이 없으면 정산 없이 모임을 종료할 수 있다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")
        val meeting = createMeeting(10L, host, "제주도 여행")

        whenever(meetingRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(meeting)
        whenever(expenseRepository.existsByMeetingId(10L)).thenReturn(false)
        whenever(meetingMemberRepository.countByMeetingIdAndStatus(10L, MeetingMemberStatus.JOINED)).thenReturn(3L)

        // when
        val response = meetingService.completeMeeting(10L, 1L)

        // then
        assertEquals(MeetingStatus.COMPLETED, response.status)
    }

    @Test
    fun `지출 내역이 있고 정산이 완료되었다면 모임을 종료할 수 있다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")
        val meeting = createMeeting(10L, host, "제주도 여행")

        whenever(meetingRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(meeting)
        whenever(expenseRepository.existsByMeetingId(10L)).thenReturn(true)
        whenever(settlementRepository.existsByMeetingIdAndStatus(10L, SettlementStatus.CLOSED)).thenReturn(true)
        whenever(meetingMemberRepository.countByMeetingIdAndStatus(10L, MeetingMemberStatus.JOINED)).thenReturn(3L)

        // when
        val response = meetingService.completeMeeting(10L, 1L)

        // then
        assertEquals(MeetingStatus.COMPLETED, response.status)
    }

    @Test
    fun `지출 내역이 있지만 정산이 완료되지 않았다면 모임을 종료할 수 없다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")
        val meeting = createMeeting(10L, host, "제주도 여행")

        whenever(meetingRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(meeting)
        whenever(expenseRepository.existsByMeetingId(10L)).thenReturn(true)
        whenever(settlementRepository.existsByMeetingIdAndStatus(10L, SettlementStatus.CLOSED)).thenReturn(false)

        // when & then
        assertThrows<MeetingSettlementNotCompletedException> {
            meetingService.completeMeeting(10L, 1L)
        }
        assertEquals(MeetingStatus.ACTIVE, meeting.status)
    }

    @Test
    fun `존재하지 않는 모임은 종료할 수 없다`() {
        // given
        whenever(meetingRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(null)

        // when & then
        assertThrows<MeetingNotFoundException> {
            meetingService.completeMeeting(10L, 1L)
        }
    }

    @Test
    fun `진행 중인 일정 투표가 있으면 모임을 종료할 수 없다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")
        val meeting = createMeeting(10L, host, "제주도 여행")

        whenever(meetingRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(meeting)
        whenever(schedulePollRepository.existsByMeetingIdAndStatus(10L, SchedulePollStatus.OPEN)).thenReturn(true)

        // when & then
        assertThrows<MeetingPollNotCompletedException> {
            meetingService.completeMeeting(10L, 1L)
        }
        assertEquals(MeetingStatus.ACTIVE, meeting.status)
    }

    @Test
    fun `진행 중인 콘텐츠 투표가 있으면 모임을 종료할 수 없다`() {
        // given
        val host = createUser(1L, "host@test.com", "모임장")
        val meeting = createMeeting(10L, host, "제주도 여행")

        whenever(meetingRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(meeting)
        whenever(contentPollRepository.existsByMeetingIdAndStatus(10L, ContentPollStatus.OPEN)).thenReturn(true)

        // when & then
        assertThrows<MeetingPollNotCompletedException> {
            meetingService.completeMeeting(10L, 1L)
        }
        assertEquals(MeetingStatus.ACTIVE, meeting.status)
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
}
