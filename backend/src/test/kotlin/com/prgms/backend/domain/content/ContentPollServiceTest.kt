package com.prgms.backend.domain.content

import com.prgms.backend.domain.content.dto.request.ContentPollRequest
import com.prgms.backend.domain.content.dto.response.ContentPollResponse
import com.prgms.backend.domain.content.entity.ContentCandidate
import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.entity.ContentVote
import com.prgms.backend.domain.content.enums.ContentPollResultSort
import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.enums.ContentPreference
import com.prgms.backend.domain.content.repository.ContentCandidateRepository
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.content.repository.ContentVoteRepository
import com.prgms.backend.domain.content.service.ContentPollService
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.meeting
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.member
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.user
import com.prgms.backend.global.exception.custom.content.ContentPollAlreadyConfirmedException
import com.prgms.backend.global.exception.custom.content.ContentPollAlreadyExistsException
import com.prgms.backend.global.exception.custom.content.ContentPollClosedException
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

class ContentPollServiceTest {
    private lateinit var contentPollRepository: ContentPollRepository
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var contentCandidateRepository: ContentCandidateRepository
    private lateinit var contentVoteRepository: ContentVoteRepository
    private lateinit var meetingMemberRepository: MeetingMemberRepository
    private lateinit var service: ContentPollService

    private lateinit var meeting: Meeting
    private lateinit var hostMember: MeetingMember
    private lateinit var member: MeetingMember
    private lateinit var poll: ContentPoll

    @BeforeEach
    fun setUp() {
        contentPollRepository = mock()
        meetingRepository = mock()
        contentCandidateRepository = mock()
        contentVoteRepository = mock()
        meetingMemberRepository = mock()
        service =
            ContentPollService(
                contentPollRepository,
                meetingRepository,
                contentCandidateRepository,
                contentVoteRepository,
                meetingMemberRepository,
            )

        meeting = meeting(MEETING_ID, user(HOST_ID, "host"))
        hostMember = member(HOST_MEMBER_ID, meeting, meeting.host)
        member = member(MEMBER_ID, meeting, user(2L, "member"))
        poll = newPoll(LocalDateTime.now().plusDays(1))

        whenever(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting))
        whenever(meetingRepository.findByIdAndDeletedAtIsNullForShare(MEETING_ID)).thenReturn(meeting)
        whenever(contentPollRepository.findByMeetingId(MEETING_ID)).thenReturn(poll)
        whenever(contentPollRepository.findByMeetingIdForUpdate(MEETING_ID)).thenReturn(poll)
        whenever(meetingMemberRepository.findByMeetingIdAndUserId(MEETING_ID, HOST_ID)).thenReturn(hostMember)
        whenever(meetingMemberRepository.findByMeetingIdAndUserId(MEETING_ID, 2L)).thenReturn(member)
        whenever(meetingMemberRepository.findAllByMeetingId(MEETING_ID))
            .thenReturn(listOf(hostMember, member))
    }

    private fun newPoll(deadline: LocalDateTime): ContentPoll {
        val contentPoll = ContentPoll(MEETING_ID, deadline)
        ReflectionTestUtils.setField(contentPoll, "id", POLL_ID)
        return contentPoll
    }

    private fun newCandidate(
        id: Long,
        candidatePoll: ContentPoll,
        createdByMemberId: Long,
    ): ContentCandidate {
        val candidate = ContentCandidate(candidatePoll, createdByMemberId, "후보$id", "")
        ReflectionTestUtils.setField(candidate, "id", id)
        return candidate
    }

    @Test
    fun `호스트는 모임에 콘텐츠 투표판을 생성`() {
        // given
        val deadline = LocalDateTime.now().plusDays(3)
        whenever(contentPollRepository.existsByMeetingId(MEETING_ID)).thenReturn(false)
        whenever(contentPollRepository.save(any<ContentPoll>())).thenAnswer { invocation ->
            invocation.getArgument<ContentPoll>(0)
        }

        // when
        val created = service.create(MEETING_ID, HOST_ID, ContentPollRequest.Create(deadline))

        // then
        assertEquals(MEETING_ID, created.meetingId)
        assertEquals(deadline, created.deadline)
        assertEquals(ContentPollStatus.OPEN, created.status)
    }

    @Test
    fun `호스트가 아니면 투표판을 생성할 수 없음`() {
        // when / then
        assertThrows<MeetingAccessDeniedException> {
            service.create(MEETING_ID, 2L, ContentPollRequest.Create(LocalDateTime.now().plusDays(1)))
        }
        verify(contentPollRepository, never()).save(any<ContentPoll>())
    }

    @Test
    fun `이미 투표판이 있는 모임에는 생성할 수 없음`() {
        // given
        whenever(contentPollRepository.existsByMeetingId(MEETING_ID)).thenReturn(true)

        // when / then
        assertThrows<ContentPollAlreadyExistsException> {
            service.create(MEETING_ID, HOST_ID, ContentPollRequest.Create(LocalDateTime.now().plusDays(1)))
        }
    }

    @Test
    fun `진행 중에는 점수를 숨기고 내 선택만 보여줌`() {
        // given
        val candidate = newCandidate(40L, poll, HOST_MEMBER_ID)
        whenever(
            contentCandidateRepository.findByContentPollIdOrderByCreatedAtAsc(POLL_ID),
        ).thenReturn(listOf(candidate))
        whenever(contentVoteRepository.findByContentCandidate_ContentPoll_Id(POLL_ID)).thenReturn(
            listOf(
                ContentVote(candidate, HOST_MEMBER_ID, ContentPreference.PREFER),
                ContentVote(candidate, MEMBER_ID, ContentPreference.DISLIKE),
            ),
        )

        // when
        val detail = service.get(MEETING_ID, HOST_ID)

        // then
        val rank = detail.candidates[0]
        assertEquals(0, rank.totalScore)
        assertEquals(ContentPreference.PREFER, rank.myPreference)
    }

    @Test
    fun `마감 후에는 점수 높은 후보 순으로 정렬`() {
        // given
        poll.close()
        val low = newCandidate(40L, poll, HOST_MEMBER_ID)
        val high = newCandidate(41L, poll, MEMBER_ID)
        whenever(
            contentCandidateRepository.findByContentPollIdOrderByCreatedAtAsc(POLL_ID),
        ).thenReturn(listOf(low, high))
        whenever(contentVoteRepository.findByContentCandidate_ContentPoll_Id(POLL_ID)).thenReturn(
            listOf(
                ContentVote(low, HOST_MEMBER_ID, ContentPreference.DISLIKE),
                ContentVote(high, HOST_MEMBER_ID, ContentPreference.PREFER),
            ),
        )

        // when
        val detail = service.get(MEETING_ID, HOST_ID)

        // then
        assertEquals(listOf(41L, 40L), detail.candidates.map(ContentPollResponse.CandidateRank::candidateId))
        assertEquals(3, detail.candidates[0].totalScore)
    }

    @Test
    fun `마감기한을 수정하고 모임 공유 잠금 후 투표판 잠금 순서로 조회`() {
        // given
        val newDeadline = LocalDateTime.now().plusDays(5)

        // when
        val updated =
            service.updateDeadline(MEETING_ID, HOST_ID, ContentPollRequest.UpdateDeadline(newDeadline))

        // then
        assertEquals(newDeadline, updated.deadline)
        val order = inOrder(meetingRepository, contentPollRepository)
        order.verify(meetingRepository).findByIdAndDeletedAtIsNullForShare(MEETING_ID)
        order.verify(contentPollRepository).findByMeetingIdForUpdate(MEETING_ID)
    }

    @Test
    fun `호스트가 아니면 마감기한을 수정할 수 없음`() {
        // when / then
        assertThrows<MeetingAccessDeniedException> {
            service.updateDeadline(MEETING_ID, 2L, ContentPollRequest.UpdateDeadline(LocalDateTime.now().plusDays(5)))
        }
        verify(contentPollRepository, never()).findByMeetingIdForUpdate(anyOrNull())
    }

    @Test
    fun `이미 마감된 투표판의 마감기한은 수정할 수 없음`() {
        // given
        poll.close()

        // when / then
        assertThrows<ContentPollClosedException> {
            service.updateDeadline(
                MEETING_ID,
                HOST_ID,
                ContentPollRequest.UpdateDeadline(LocalDateTime.now().plusDays(5)),
            )
        }
    }

    @Test
    fun `결과에 후보별 집계와 미응답 수, 멤버별 선택이 후보 정렬 순서대로 담김`() {
        // given
        poll.close()
        val first = newCandidate(40L, poll, HOST_MEMBER_ID)
        val second = newCandidate(41L, poll, MEMBER_ID)
        whenever(contentCandidateRepository.findByContentPollIdOrderByCreatedAtAsc(POLL_ID))
            .thenReturn(listOf(first, second))
        whenever(contentVoteRepository.findByContentCandidate_ContentPoll_Id(POLL_ID)).thenReturn(
            listOf(
                ContentVote(first, HOST_MEMBER_ID, ContentPreference.PREFER),
                ContentVote(first, MEMBER_ID, ContentPreference.DISLIKE),
                ContentVote(second, HOST_MEMBER_ID, ContentPreference.AVAILABLE),
            ),
        )

        // when
        val results = service.getResults(MEETING_ID, HOST_ID, ContentPollResultSort.SCORE)

        // then
        assertEquals(2, results.joinedCount)
        val firstResult = results.candidates[0]
        assertEquals(40L, firstResult.candidateId)
        assertEquals(4, firstResult.totalScore)
        assertEquals(1, firstResult.preferCount)
        assertEquals(1, firstResult.dislikeCount)
        assertEquals(0, firstResult.noResponseCount)
        assertEquals("host", firstResult.createdByNickname)
        assertEquals(1, results.candidates[1].noResponseCount)

        val memberResult = results.members[1]
        assertEquals(listOf(ContentPreference.DISLIKE, null), memberResult.preferences)
        assertEquals(1, memberResult.responseCount)
        assertEquals(true, results.members[0].host)
    }

    @Test
    fun `마감 후 탈퇴한 사람의 표는 참여 인원·미응답 수·인원별 결과에 함께 포함되어 합계가 맞음`() {
        // given
        poll.close()
        val first = newCandidate(40L, poll, MEMBER_ID)
        whenever(contentCandidateRepository.findByContentPollIdOrderByCreatedAtAsc(POLL_ID))
            .thenReturn(listOf(first))
        whenever(contentVoteRepository.findByContentCandidate_ContentPoll_Id(POLL_ID)).thenReturn(
            listOf(
                ContentVote(first, HOST_MEMBER_ID, ContentPreference.PREFER),
                ContentVote(first, MEMBER_ID, ContentPreference.DISLIKE),
            ),
        )
        member.leave()

        // when
        val results = service.getResults(MEETING_ID, HOST_ID, ContentPollResultSort.SCORE)

        // then
        val result = results.candidates[0]
        assertEquals(2, results.joinedCount)
        assertEquals(2, result.responseCount)
        assertEquals(0, result.noResponseCount)
        assertEquals("member", result.createdByNickname)
        assertEquals(2, results.members.size)
    }

    @Test
    fun `마감된 투표판의 후보를 확정하고 모임 공유 잠금 후 투표판 잠금 순서로 조회`() {
        // given
        poll.close()
        val candidate = newCandidate(40L, poll, HOST_MEMBER_ID)
        whenever(contentCandidateRepository.findById(40L)).thenReturn(Optional.of(candidate))

        // when
        val confirmed = service.confirm(MEETING_ID, HOST_ID, ContentPollRequest.Confirm(40L))

        // then
        assertEquals(40L, confirmed.confirmedCandidateId)
        val order = inOrder(meetingRepository, contentPollRepository)
        order.verify(meetingRepository).findByIdAndDeletedAtIsNullForShare(MEETING_ID)
        order.verify(contentPollRepository).findByMeetingIdForUpdate(MEETING_ID)
    }

    @Test
    fun `호스트가 아니면 후보를 확정할 수 없음`() {
        // when / then
        assertThrows<MeetingAccessDeniedException> {
            service.confirm(MEETING_ID, 2L, ContentPollRequest.Confirm(40L))
        }
    }

    @Test
    fun `이미 확정된 투표판은 다시 확정할 수 없음`() {
        // given
        poll.close()
        poll.confirm(40L)

        // when / then
        assertThrows<ContentPollAlreadyConfirmedException> {
            service.confirm(MEETING_ID, HOST_ID, ContentPollRequest.Confirm(41L))
        }
    }

    private companion object {
        const val HOST_ID = 1L
        const val MEETING_ID = 10L
        const val POLL_ID = 20L
        const val HOST_MEMBER_ID = 30L
        const val MEMBER_ID = 31L
    }
}
