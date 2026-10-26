package com.prgms.backend.domain.meeting

import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.repository.ContentVoteRepository
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.meeting.service.MeetingMemberService
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import com.prgms.backend.domain.schedule.vote.repository.ScheduleVoteRepository
import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException
import com.prgms.backend.global.exception.custom.meeting.MeetingHostCannotLeaveException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberAlreadyLeftException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberHasUnsettledExpenseException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

internal class MeetingMemberServiceTest {
    private lateinit var meetingMemberRepository: MeetingMemberRepository
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var settlementRepository: SettlementRepository
    private lateinit var scheduleVoteRepository: ScheduleVoteRepository
    private lateinit var contentVoteRepository: ContentVoteRepository
    private lateinit var meetingMemberService: MeetingMemberService
    private lateinit var meeting: Meeting
    private lateinit var hostMember: MeetingMember
    private lateinit var member: MeetingMember

    @BeforeEach
    fun setUp() {
        meetingMemberRepository = mock()
        meetingRepository = mock()
        expenseRepository = mock()
        settlementRepository = mock()
        scheduleVoteRepository = mock()
        contentVoteRepository = mock()

        meetingMemberService =
            MeetingMemberService(
                meetingMemberRepository = meetingMemberRepository,
                meetingRepository = meetingRepository,
                expenseRepository = expenseRepository,
                settlementRepository = settlementRepository,
                scheduleVoteRepository = scheduleVoteRepository,
                contentVoteRepository = contentVoteRepository,
            )

        val host = User("host@test.com", "모임장", "password")
        ReflectionTestUtils.setField(host, "id", 1L)

        val user = User("member@test.com", "일반 모임원", "password")
        ReflectionTestUtils.setField(user, "id", 2L)

        meeting = Meeting(host, "테스트 모임", "모임 설명")
        ReflectionTestUtils.setField(meeting, "id", 10L)

        hostMember = MeetingMember(meeting, host)
        ReflectionTestUtils.setField(hostMember, "id", 100L)

        member = MeetingMember(meeting, user)
        ReflectionTestUtils.setField(member, "id", 200L)

        // 존재하는 진행 중 모임과 참여 중인 모임원을 준비한다.
        whenever(meetingRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(meeting)
        whenever(meetingMemberRepository.findByMeetingIdAndUserId(10L, 1L)).thenReturn(hostMember)
        whenever(meetingMemberRepository.findByMeetingIdAndUserId(10L, 2L)).thenReturn(member)

        // 기본 조건은 정산 없음, 일반 모임원은 지출 관련자가 아님이다.
        whenever(settlementRepository.findByMeetingId(10L)).thenReturn(null)
        whenever(expenseRepository.existsMemberInExpense(10L, 200L)).thenReturn(false)
    }

    @Test
    fun `지출에 관련되지 않은 일반 모임원은 탈퇴할 수 있다`() {
        // given
        val originalJoinedAt = member.joinedAt

        // when
        val response = meetingMemberService.leaveMeeting(10L, 2L)

        // then: 실제 엔티티의 상태
        assertEquals(MeetingMemberStatus.LEFT, member.status)
        assertNotNull(member.leftAt)
        assertEquals(originalJoinedAt, member.joinedAt)

        // then: 반환된 응답
        assertEquals(200L, response.id)
        assertEquals(10L, response.meetingId)
        assertEquals(2L, response.userId)
        assertEquals(MeetingMemberStatus.LEFT, response.status)
        assertEquals(member.leftAt, response.leftAt)

        // then: 진행 중 투표 정리 요청
        verify(scheduleVoteRepository).deleteOpenVotesByMeetingMember(
            eq(10L),
            eq(200L),
            eq(SchedulePollStatus.OPEN),
            any<LocalDateTime>(),
        )
        verify(contentVoteRepository).deleteOpenVotesByMeetingMember(
            eq(10L),
            eq(200L),
            eq(ContentPollStatus.OPEN),
            any<LocalDateTime>(),
        )
    }

    @Test
    fun `진행 중인 모임의 모임장은 탈퇴할 수 없다`() {
        // given
        val originalJoinedAt = hostMember.joinedAt

        // when & then
        assertThrows<MeetingHostCannotLeaveException> {
            meetingMemberService.leaveMeeting(10L, 1L)
        }
        assertEquals(MeetingMemberStatus.JOINED, hostMember.status)
        assertEquals(originalJoinedAt, hostMember.joinedAt)
        assertNull(hostMember.leftAt)
        verifyNoInteractions(settlementRepository, expenseRepository, scheduleVoteRepository, contentVoteRepository)
    }

    @Test
    fun `완료된 모임의 모임장은 탈퇴할 수 있다`() {
        // given
        meeting.complete()
        whenever(expenseRepository.existsMemberInExpense(10L, 100L)).thenReturn(false)

        // when
        val response = meetingMemberService.leaveMeeting(10L, 1L)

        // then
        assertEquals(MeetingMemberStatus.LEFT, hostMember.status)
        assertNotNull(hostMember.leftAt)
        assertEquals(100L, response.id)

        // 모임 탈퇴가 모임 자체를 삭제하면 안 된다.
        assertTrue(meeting.isCompleted())
        assertNull(meeting.deletedAt)
    }

    @Test
    fun `최종 정산이 없고 지출에 관련된 모임원은 탈퇴할 수 없다`() {
        // given
        whenever(expenseRepository.existsMemberInExpense(10L, 200L)).thenReturn(true)
        val originalJoinedAt = member.joinedAt

        // when & then
        assertThrows<MeetingMemberHasUnsettledExpenseException> {
            meetingMemberService.leaveMeeting(10L, 2L)
        }
        assertEquals(MeetingMemberStatus.JOINED, member.status)
        assertEquals(originalJoinedAt, member.joinedAt)
        assertNull(member.leftAt)
        verifyNoInteractions(scheduleVoteRepository, contentVoteRepository)
    }

    @Test
    fun `OPEN 정산이 있고 지출에 관련된 모임원은 탈퇴할 수 없다`() {
        // given: 생성 직후 Settlement는 OPEN이다.
        val settlement = Settlement(10L)
        whenever(settlementRepository.findByMeetingId(10L)).thenReturn(settlement)
        whenever(expenseRepository.existsMemberInExpense(10L, 200L)).thenReturn(true)

        // when & then
        assertThrows<MeetingMemberHasUnsettledExpenseException> {
            meetingMemberService.leaveMeeting(10L, 2L)
        }
        assertEquals(MeetingMemberStatus.JOINED, member.status)
        assertNull(member.leftAt)
        verifyNoInteractions(scheduleVoteRepository, contentVoteRepository)
    }

    @Test
    fun `OPEN 정산이 있어도 지출에 관련되지 않은 모임원은 탈퇴할 수 있다`() {
        // given
        val settlement = Settlement(10L)
        whenever(settlementRepository.findByMeetingId(10L)).thenReturn(settlement)

        // 지출 관련 여부는 setUp에서 false로 설정되어 있다.

        // when
        val response = meetingMemberService.leaveMeeting(10L, 2L)

        // then
        assertEquals(MeetingMemberStatus.LEFT, response.status)
        assertNotNull(member.leftAt)
    }

    @Test
    fun `최종 정산이 CLOSED이면 지출 관련자도 탈퇴할 수 있다`() {
        // given
        val settlement = Settlement(10L)
        settlement.close(100L)
        whenever(settlementRepository.findByMeetingId(10L)).thenReturn(settlement)
        whenever(expenseRepository.existsMemberInExpense(10L, 200L)).thenReturn(true)

        // when
        val response = meetingMemberService.leaveMeeting(10L, 2L)

        // then
        assertEquals(MeetingMemberStatus.LEFT, response.status)
        assertNotNull(member.leftAt)

        // CLOSED라면 지출 관련 여부로 탈퇴를 제한하지 않는다.
        verify(expenseRepository, never()).existsMemberInExpense(any(), any())
    }

    @Test
    fun `존재하지 않거나 삭제된 모임에서는 탈퇴할 수 없다`() {
        // given
        whenever(meetingRepository.findByIdAndDeletedAtIsNullForUpdate(10L)).thenReturn(null)

        // when & then
        assertThrows<MeetingNotFoundException> {
            meetingMemberService.leaveMeeting(10L, 2L)
        }
        verifyNoInteractions(
            meetingMemberRepository,
            settlementRepository,
            expenseRepository,
            scheduleVoteRepository,
            contentVoteRepository,
        )
    }

    @Test
    fun `가입한 적 없는 모임에서는 탈퇴할 수 없다`() {
        // given
        whenever(meetingMemberRepository.findByMeetingIdAndUserId(10L, 2L)).thenReturn(null)

        // when & then
        assertThrows<MeetingMemberNotFoundException> {
            meetingMemberService.leaveMeeting(10L, 2L)
        }
        verifyNoInteractions(settlementRepository, expenseRepository, scheduleVoteRepository, contentVoteRepository)
    }

    @Test
    fun `이미 LEFT인 모임원은 다시 탈퇴할 수 없고 기존 탈퇴 시각도 유지된다`() {
        // given
        member.leave()
        val originalJoinedAt = member.joinedAt
        val originalLeftAt = member.leftAt

        // when & then
        assertThrows<MeetingMemberAlreadyLeftException> {
            meetingMemberService.leaveMeeting(10L, 2L)
        }
        assertEquals(MeetingMemberStatus.LEFT, member.status)
        assertEquals(originalJoinedAt, member.joinedAt)
        assertEquals(originalLeftAt, member.leftAt)
        verifyNoInteractions(settlementRepository, expenseRepository, scheduleVoteRepository, contentVoteRepository)
    }

    @Test
    fun `참여 중인 모임원은 현재 모임원 목록을 조회할 수 있다`() {
        // given
        whenever(meetingRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(meeting)
        whenever(
            meetingMemberRepository.existsByMeetingIdAndUserIdAndStatus(10L, 2L, MeetingMemberStatus.JOINED),
        ).thenReturn(true)
        whenever(
            meetingMemberRepository.findAllByMeetingIdAndStatus(10L, MeetingMemberStatus.JOINED),
        ).thenReturn(listOf(hostMember, member))

        // when
        val responses = meetingMemberService.getMeetingMembers(10L, 2L)

        // then
        assertEquals(2, responses.size)
        assertEquals(100L, responses[0].id)
        assertEquals(1L, responses[0].userId)
        assertEquals(MeetingMemberStatus.JOINED, responses[0].status)
        assertEquals(200L, responses[1].id)
        assertEquals(2L, responses[1].userId)
        assertEquals("일반 모임원", responses[1].nickname)
    }

    @Test
    fun `현재 참여 중인 모임원이 아니면 모임원 목록을 조회할 수 없다`() {
        // given
        whenever(meetingRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(meeting)
        whenever(
            meetingMemberRepository.existsByMeetingIdAndUserIdAndStatus(10L, 2L, MeetingMemberStatus.JOINED),
        ).thenReturn(false)

        // when & then
        assertThrows<MeetingAccessDeniedException> {
            meetingMemberService.getMeetingMembers(10L, 2L)
        }
        verify(meetingMemberRepository, never()).findAllByMeetingIdAndStatus(any(), any())
    }

    @Test
    fun `존재하지 않거나 삭제된 모임의 모임원 목록은 조회할 수 없다`() {
        // given
        whenever(meetingRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(null)

        // when & then
        assertThrows<MeetingNotFoundException> {
            meetingMemberService.getMeetingMembers(10L, 2L)
        }
        verifyNoInteractions(meetingMemberRepository)
    }
}
