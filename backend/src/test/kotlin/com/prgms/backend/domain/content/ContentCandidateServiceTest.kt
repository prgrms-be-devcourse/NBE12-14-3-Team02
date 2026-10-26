package com.prgms.backend.domain.content

import com.prgms.backend.domain.content.dto.request.ContentCandidateRequest
import com.prgms.backend.domain.content.entity.ContentCandidate
import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.repository.ContentCandidateRepository
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.content.repository.ContentVoteRepository
import com.prgms.backend.domain.content.service.ContentCandidateService
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.meeting
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.member
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.user
import com.prgms.backend.global.exception.custom.content.ContentCandidateHasVotesException
import com.prgms.backend.global.exception.custom.content.ContentPollClosedException
import com.prgms.backend.global.exception.custom.content.DuplicateContentCandidateTitleException
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberNotFoundException
import com.prgms.backend.support.AuditFields.filled
import org.junit.jupiter.api.Assertions.assertEquals
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

class ContentCandidateServiceTest {
    private lateinit var contentCandidateRepository: ContentCandidateRepository
    private lateinit var contentPollRepository: ContentPollRepository
    private lateinit var contentVoteRepository: ContentVoteRepository
    private lateinit var meetingMemberRepository: MeetingMemberRepository
    private lateinit var service: ContentCandidateService

    private lateinit var member: MeetingMember
    private lateinit var poll: ContentPoll
    private lateinit var candidate: ContentCandidate

    @BeforeEach
    fun setUp() {
        contentCandidateRepository = mock()
        contentPollRepository = mock()
        contentVoteRepository = mock()
        meetingMemberRepository = mock()
        service =
            ContentCandidateService(
                contentCandidateRepository,
                contentPollRepository,
                contentVoteRepository,
                meetingMemberRepository,
            )

        val meeting = meeting(MEETING_ID, user(USER_ID, "host"))
        member = member(MEMBER_ID, meeting, meeting.host)
        poll = newPoll(POLL_ID)
        candidate = newCandidate(CANDIDATE_ID, poll, MEMBER_ID, "기존 후보")

        whenever(meetingMemberRepository.findByMeetingIdAndUserId(MEETING_ID, USER_ID)).thenReturn(member)
        whenever(contentPollRepository.findByMeetingId(MEETING_ID)).thenReturn(poll)
        whenever(contentCandidateRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate))
        whenever(contentCandidateRepository.save(any<ContentCandidate>()))
            .thenAnswer { invocation -> filled(invocation.getArgument<ContentCandidate>(0)) }
    }

    private fun newPoll(id: Long): ContentPoll {
        val contentPoll = ContentPoll(MEETING_ID, LocalDateTime.now().plusDays(1))
        ReflectionTestUtils.setField(contentPoll, "id", id)
        return contentPoll
    }

    private fun newCandidate(
        id: Long,
        candidatePoll: ContentPoll,
        createdByMemberId: Long,
        title: String,
    ): ContentCandidate {
        val contentCandidate = ContentCandidate(candidatePoll, createdByMemberId, title, "설명")
        ReflectionTestUtils.setField(contentCandidate, "id", id)
        return filled(contentCandidate)
    }

    @Test
    fun `참여 중인 멤버는 후보를 생성`() {
        // given
        whenever(contentCandidateRepository.existsByContentPollIdAndTitle(POLL_ID, "새 후보")).thenReturn(false)

        // when
        val saved =
            service.create(
                MEETING_ID,
                USER_ID,
                ContentCandidateRequest.Create("새 후보", "설명"),
            )

        // then
        assertEquals("새 후보", saved.title)
        assertEquals(MEMBER_ID, saved.createdByMemberId)
        assertEquals(POLL_ID, saved.contentPollId)
    }

    @Test
    fun `모임 멤버가 아니면 후보를 생성할 수 없음`() {
        // given
        whenever(meetingMemberRepository.findByMeetingIdAndUserId(MEETING_ID, USER_ID)).thenReturn(null)

        // when / then
        assertThrows<MeetingMemberNotFoundException> {
            service.create(MEETING_ID, USER_ID, ContentCandidateRequest.Create("새 후보", "설명"))
        }
        verify(contentCandidateRepository, never()).save(any<ContentCandidate>())
    }

    @Test
    fun `탈퇴한 멤버는 후보를 생성할 수 없음`() {
        // given
        member.leave()

        // when / then
        assertThrows<MeetingAccessDeniedException> {
            service.create(MEETING_ID, USER_ID, ContentCandidateRequest.Create("새 후보", "설명"))
        }
    }

    @Test
    fun `마감된 투표판에는 후보를 생성할 수 없음`() {
        // given
        poll.close()

        // when / then
        assertThrows<ContentPollClosedException> {
            service.create(MEETING_ID, USER_ID, ContentCandidateRequest.Create("새 후보", "설명"))
        }
    }

    @Test
    fun `후보를 만든 멤버는 투표가 없으면 후보를 수정`() {
        // when
        val saved =
            service.update(
                MEETING_ID,
                CANDIDATE_ID,
                USER_ID,
                ContentCandidateRequest.Update("수정된 후보", "수정된 설명"),
            )

        // then
        assertEquals("수정된 후보", saved.title)
        assertEquals("수정된 설명", candidate.description)
    }

    @Test
    fun `이미 투표가 달린 후보는 수정할 수 없음`() {
        // given
        whenever(contentVoteRepository.existsByContentCandidateId(CANDIDATE_ID)).thenReturn(true)

        // when / then
        assertThrows<ContentCandidateHasVotesException> {
            service.update(MEETING_ID, CANDIDATE_ID, USER_ID, ContentCandidateRequest.Update("수정", "설명"))
        }
        assertEquals("기존 후보", candidate.title)
    }

    @Test
    fun `다른 후보와 제목이 겹치게 수정할 수 없음`() {
        // given
        whenever(contentCandidateRepository.existsByContentPollIdAndTitleAndIdNot(POLL_ID, "중복 제목", CANDIDATE_ID))
            .thenReturn(true)

        // when / then
        assertThrows<DuplicateContentCandidateTitleException> {
            service.update(
                MEETING_ID,
                CANDIDATE_ID,
                USER_ID,
                ContentCandidateRequest.Update("중복 제목", "설명"),
            )
        }
        assertEquals("기존 후보", candidate.title)
    }

    @Test
    fun `후보를 만든 멤버는 투표가 없으면 후보를 삭제`() {
        // when
        service.delete(MEETING_ID, CANDIDATE_ID, USER_ID)

        // then
        verify(contentCandidateRepository).delete(candidate)
    }

    @Test
    fun `이미 투표가 달린 후보는 삭제할 수 없음`() {
        // given
        whenever(contentVoteRepository.existsByContentCandidateId(CANDIDATE_ID)).thenReturn(true)

        // when / then
        assertThrows<ContentCandidateHasVotesException> {
            service.delete(MEETING_ID, CANDIDATE_ID, USER_ID)
        }
        verify(contentCandidateRepository, never()).delete(any<ContentCandidate>())
    }

    private companion object {
        const val MEETING_ID = 10L
        const val POLL_ID = 20L
        const val MEMBER_ID = 30L
        const val CANDIDATE_ID = 40L
        const val USER_ID = 1L
    }
}
