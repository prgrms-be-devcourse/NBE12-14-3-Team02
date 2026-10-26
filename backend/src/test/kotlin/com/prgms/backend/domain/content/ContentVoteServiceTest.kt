package com.prgms.backend.domain.content

import com.prgms.backend.domain.content.dto.request.ContentVoteRequest
import com.prgms.backend.domain.content.entity.ContentCandidate
import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.entity.ContentVote
import com.prgms.backend.domain.content.enums.ContentPreference
import com.prgms.backend.domain.content.repository.ContentCandidateRepository
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.content.repository.ContentVoteRepository
import com.prgms.backend.domain.content.service.ContentVoteService
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.meeting
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.member
import com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.user
import com.prgms.backend.global.exception.custom.content.ContentCandidateNotFoundException
import com.prgms.backend.global.exception.custom.content.ContentPollClosedException
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException
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

class ContentVoteServiceTest {
    private lateinit var contentVoteRepository: ContentVoteRepository
    private lateinit var contentCandidateRepository: ContentCandidateRepository
    private lateinit var contentPollRepository: ContentPollRepository
    private lateinit var meetingMemberRepository: MeetingMemberRepository
    private lateinit var service: ContentVoteService

    private lateinit var member: MeetingMember
    private lateinit var poll: ContentPoll
    private lateinit var candidate: ContentCandidate

    @BeforeEach
    fun setUp() {
        contentVoteRepository = mock()
        contentCandidateRepository = mock()
        contentPollRepository = mock()
        meetingMemberRepository = mock()
        service =
            ContentVoteService(
                contentVoteRepository,
                contentCandidateRepository,
                contentPollRepository,
                meetingMemberRepository,
            )

        val meeting = meeting(MEETING_ID, user(USER_ID, "host"))
        member = member(MEMBER_ID, meeting, meeting.host)
        poll = newPoll(POLL_ID)
        candidate = newCandidate(CANDIDATE_ID, poll)

        whenever(meetingMemberRepository.findByMeetingIdAndUserId(MEETING_ID, USER_ID)).thenReturn(member)
        whenever(contentPollRepository.findByMeetingId(MEETING_ID)).thenReturn(poll)
        whenever(contentCandidateRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate))
        whenever(contentVoteRepository.findByContentCandidateIdAndMeetingMemberId(CANDIDATE_ID, MEMBER_ID))
            .thenReturn(null)
        whenever(contentVoteRepository.save(any<ContentVote>()))
            .thenAnswer { invocation -> filled(invocation.getArgument<ContentVote>(0)) }
    }

    private fun newPoll(id: Long): ContentPoll {
        val contentPoll = ContentPoll(MEETING_ID, LocalDateTime.now().plusDays(1))
        ReflectionTestUtils.setField(contentPoll, "id", id)
        return contentPoll
    }

    private fun newCandidate(
        id: Long,
        candidatePoll: ContentPoll,
    ): ContentCandidate {
        val contentCandidate = ContentCandidate(candidatePoll, MEMBER_ID, "후보", "설명")
        ReflectionTestUtils.setField(contentCandidate, "id", id)
        return contentCandidate
    }

    @Test
    fun `처음 투표하면 새 투표를 저장`() {
        // when
        val saved =
            service.upsert(
                MEETING_ID,
                USER_ID,
                ContentVoteRequest.Submit(CANDIDATE_ID, ContentPreference.PREFER),
            )

        // then
        assertEquals(CANDIDATE_ID, saved.candidateId)
        assertEquals(MEMBER_ID, saved.meetingMemberId)
        assertEquals(ContentPreference.PREFER, saved.preference)
        verify(contentVoteRepository).save(any<ContentVote>())
    }

    @Test
    fun `이미 투표했으면 새로 저장하지 않고 선호도만 변경`() {
        // given
        val existing = filled(ContentVote(candidate, MEMBER_ID, ContentPreference.PREFER))
        whenever(contentVoteRepository.findByContentCandidateIdAndMeetingMemberId(CANDIDATE_ID, MEMBER_ID))
            .thenReturn(existing)

        // when
        val saved =
            service.upsert(
                MEETING_ID,
                USER_ID,
                ContentVoteRequest.Submit(CANDIDATE_ID, ContentPreference.DISLIKE),
            )

        // then
        assertEquals(ContentPreference.DISLIKE, saved.preference)
        assertEquals(ContentPreference.DISLIKE, existing.preference)
        verify(contentVoteRepository, never()).save(any<ContentVote>())
    }

    @Test
    fun `탈퇴한 멤버는 투표할 수 없음`() {
        // given
        member.leave()

        // when / then
        assertThrows<MeetingAccessDeniedException> {
            service.upsert(
                MEETING_ID,
                USER_ID,
                ContentVoteRequest.Submit(CANDIDATE_ID, ContentPreference.PREFER),
            )
        }
    }

    @Test
    fun `마감된 투표판에는 투표할 수 없음`() {
        // given
        poll.close()

        // when / then
        assertThrows<ContentPollClosedException> {
            service.upsert(
                MEETING_ID,
                USER_ID,
                ContentVoteRequest.Submit(CANDIDATE_ID, ContentPreference.PREFER),
            )
        }
        verify(contentVoteRepository, never()).save(any<ContentVote>())
    }

    @Test
    fun `다른 투표판의 후보에는 투표할 수 없음`() {
        // given
        whenever(contentCandidateRepository.findById(CANDIDATE_ID))
            .thenReturn(Optional.of(newCandidate(CANDIDATE_ID, newPoll(999L))))

        // when / then
        assertThrows<ContentCandidateNotFoundException> {
            service.upsert(
                MEETING_ID,
                USER_ID,
                ContentVoteRequest.Submit(CANDIDATE_ID, ContentPreference.PREFER),
            )
        }
        verify(contentVoteRepository, never()).save(any<ContentVote>())
    }

    private companion object {
        const val MEETING_ID = 10L
        const val POLL_ID = 20L
        const val MEMBER_ID = 30L
        const val CANDIDATE_ID = 40L
        const val USER_ID = 1L
    }
}
