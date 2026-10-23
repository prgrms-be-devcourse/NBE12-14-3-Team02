package com.prgms.backend.domain.content;

import com.prgms.backend.domain.content.enums.ContentPollResultSort;
import com.prgms.backend.domain.content.enums.ContentPollStatus;
import com.prgms.backend.domain.content.enums.ContentPreference;
import com.prgms.backend.domain.content.dto.request.ContentPollRequest;
import com.prgms.backend.domain.content.dto.response.ContentPollResponse;
import com.prgms.backend.domain.content.dto.response.ContentPollResultsResponse;
import com.prgms.backend.domain.content.entity.ContentCandidate;
import com.prgms.backend.domain.content.entity.ContentPoll;
import com.prgms.backend.domain.content.entity.ContentVote;
import com.prgms.backend.domain.content.repository.ContentCandidateRepository;
import com.prgms.backend.domain.content.repository.ContentPollRepository;
import com.prgms.backend.domain.content.repository.ContentVoteRepository;
import com.prgms.backend.domain.content.service.ContentPollService;
import com.prgms.backend.domain.meeting.entity.Meeting;
import com.prgms.backend.domain.meeting.entity.MeetingMember;
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository;
import com.prgms.backend.domain.meeting.repository.MeetingRepository;
import com.prgms.backend.global.exception.custom.content.ContentPollAlreadyConfirmedException;
import com.prgms.backend.global.exception.custom.content.ContentPollAlreadyExistsException;
import com.prgms.backend.global.exception.custom.content.ContentPollClosedException;
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.meeting;
import static com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.member;
import static com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ContentPollServiceTest {

    private static final long HOST_ID = 1L;
    private static final long MEETING_ID = 10L;
    private static final long POLL_ID = 20L;
    private static final long HOST_MEMBER_ID = 30L;
    private static final long MEMBER_ID = 31L;

    private ContentPollRepository contentPollRepository;
    private MeetingRepository meetingRepository;
    private ContentCandidateRepository contentCandidateRepository;
    private ContentVoteRepository contentVoteRepository;
    private MeetingMemberRepository meetingMemberRepository;
    private ContentPollService service;

    private Meeting meeting;
    private MeetingMember hostMember;
    private MeetingMember member;
    private ContentPoll poll;

    @BeforeEach
    void setUp() {
        contentPollRepository = mock(ContentPollRepository.class);
        meetingRepository = mock(MeetingRepository.class);
        contentCandidateRepository = mock(ContentCandidateRepository.class);
        contentVoteRepository = mock(ContentVoteRepository.class);
        meetingMemberRepository = mock(MeetingMemberRepository.class);
        service = new ContentPollService(contentPollRepository, meetingRepository,
                contentCandidateRepository, contentVoteRepository, meetingMemberRepository);

        meeting = meeting(MEETING_ID, user(HOST_ID, "host"));
        hostMember = member(HOST_MEMBER_ID, meeting, meeting.getHost());
        member = member(MEMBER_ID, meeting, user(2L, "member"));
        poll = poll(LocalDateTime.now().plusDays(1));

        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(meetingRepository.findByIdAndDeletedAtIsNullForShare(MEETING_ID)).thenReturn(meeting);
        when(contentPollRepository.findByMeetingId(MEETING_ID)).thenReturn(poll);
        when(contentPollRepository.findByMeetingIdForUpdate(MEETING_ID)).thenReturn(poll);
        when(meetingMemberRepository.findByMeetingIdAndUserId(MEETING_ID, HOST_ID)).thenReturn(hostMember);
        when(meetingMemberRepository.findByMeetingIdAndUserId(MEETING_ID, 2L)).thenReturn(member);
        when(meetingMemberRepository.findAllByMeetingId(MEETING_ID))
                .thenReturn(List.of(hostMember, member));
    }

    private ContentPoll poll(LocalDateTime deadline) {
        ContentPoll contentPoll = new ContentPoll(MEETING_ID, deadline);
        ReflectionTestUtils.setField(contentPoll, "id", POLL_ID);
        return contentPoll;
    }

    private ContentCandidate candidate(long id, ContentPoll candidatePoll, long createdByMemberId) {
        ContentCandidate candidate = new ContentCandidate(candidatePoll, createdByMemberId, "후보" + id, "");
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }

    @Test
    @DisplayName("호스트는 모임에 콘텐츠 투표판을 생성")
    void createPoll() {

        // given
        LocalDateTime deadline = LocalDateTime.now().plusDays(3);
        when(contentPollRepository.existsByMeetingId(MEETING_ID)).thenReturn(false);
        when(contentPollRepository.save(any(ContentPoll.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        ContentPollResponse.Created created = service.create(MEETING_ID, HOST_ID,
                new ContentPollRequest.Create(deadline));

        // then
        assertEquals(MEETING_ID, created.getMeetingId());
        assertEquals(deadline, created.getDeadline());
        assertEquals(ContentPollStatus.OPEN, created.getStatus());
    }

    @Test
    @DisplayName("호스트가 아니면 투표판을 생성할 수 없음")
    void createPollByNonHost() {

        // when / then
        assertThrows(MeetingAccessDeniedException.class, () ->
                service.create(MEETING_ID, 2L, new ContentPollRequest.Create(LocalDateTime.now().plusDays(1))));
        verify(contentPollRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 투표판이 있는 모임에는 생성할 수 없음")
    void createDuplicatePoll() {

        // given
        when(contentPollRepository.existsByMeetingId(MEETING_ID)).thenReturn(true);

        // when / then
        assertThrows(ContentPollAlreadyExistsException.class, () ->
                service.create(MEETING_ID, HOST_ID, new ContentPollRequest.Create(LocalDateTime.now().plusDays(1))));
    }

    @Test
    @DisplayName("진행 중에는 점수를 숨기고 내 선택만 보여줌")
    void getOpenPollHidesScore() {

        // given
        ContentCandidate candidate = candidate(40L, poll, HOST_MEMBER_ID);
        when(contentCandidateRepository.findByContentPollIdOrderByCreatedAtAsc(POLL_ID)).thenReturn(List.of(candidate));
        when(contentVoteRepository.findByContentCandidate_ContentPoll_Id(POLL_ID)).thenReturn(List.of(
                new ContentVote(candidate, HOST_MEMBER_ID, ContentPreference.PREFER),
                new ContentVote(candidate, MEMBER_ID, ContentPreference.DISLIKE)
        ));

        // when
        ContentPollResponse.Detail detail = service.get(MEETING_ID, HOST_ID);

        // then
        ContentPollResponse.CandidateRank rank = detail.getCandidates().get(0);
        assertEquals(0, rank.getTotalScore());
        assertEquals(ContentPreference.PREFER, rank.getMyPreference());
    }

    @Test
    @DisplayName("마감 후에는 점수 높은 후보 순으로 정렬")
    void getClosedPollSortsByScore() {

        // given
        poll.close();
        ContentCandidate low = candidate(40L, poll, HOST_MEMBER_ID);
        ContentCandidate high = candidate(41L, poll, MEMBER_ID);
        when(contentCandidateRepository.findByContentPollIdOrderByCreatedAtAsc(POLL_ID)).thenReturn(List.of(low, high));
        when(contentVoteRepository.findByContentCandidate_ContentPoll_Id(POLL_ID)).thenReturn(List.of(
                new ContentVote(low, HOST_MEMBER_ID, ContentPreference.DISLIKE),
                new ContentVote(high, HOST_MEMBER_ID, ContentPreference.PREFER)
        ));

        // when
        ContentPollResponse.Detail detail = service.get(MEETING_ID, HOST_ID);

        // then
        assertEquals(List.of(41L, 40L),
                detail.getCandidates().stream().map(ContentPollResponse.CandidateRank::getCandidateId).toList());
        assertEquals(3, detail.getCandidates().get(0).getTotalScore());
    }

    @Test
    @DisplayName("마감기한을 수정하고 모임 공유 잠금 후 투표판 잠금 순서로 조회")
    void updateDeadline() {

        // given
        LocalDateTime newDeadline = LocalDateTime.now().plusDays(5);

        // when
        ContentPollResponse.DeadlineUpdate updated = service.updateDeadline(MEETING_ID, HOST_ID,
                new ContentPollRequest.UpdateDeadline(newDeadline));

        // then
        assertEquals(newDeadline, updated.getDeadline());
        InOrder order = inOrder(meetingRepository, contentPollRepository);
        order.verify(meetingRepository).findByIdAndDeletedAtIsNullForShare(MEETING_ID);
        order.verify(contentPollRepository).findByMeetingIdForUpdate(MEETING_ID);
    }

    @Test
    @DisplayName("호스트가 아니면 마감기한을 수정할 수 없음")
    void updateDeadlineByNonHost() {

        // when / then
        assertThrows(MeetingAccessDeniedException.class, () -> service.updateDeadline(MEETING_ID, 2L,
                new ContentPollRequest.UpdateDeadline(LocalDateTime.now().plusDays(5))));
        verify(contentPollRepository, never()).findByMeetingIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("이미 마감된 투표판의 마감기한은 수정할 수 없음")
    void updateDeadlineOfClosedPoll() {

        // given
        poll.close();

        // when / then
        assertThrows(ContentPollClosedException.class, () -> service.updateDeadline(MEETING_ID, HOST_ID,
                new ContentPollRequest.UpdateDeadline(LocalDateTime.now().plusDays(5))));
    }

    @Test
    @DisplayName("결과에 후보별 집계와 미응답 수, 멤버별 선택이 후보 정렬 순서대로 담김")
    void getResultsAggregates() {

        // given
        poll.close();
        ContentCandidate first = candidate(40L, poll, HOST_MEMBER_ID);
        ContentCandidate second = candidate(41L, poll, MEMBER_ID);
        when(contentCandidateRepository.findByContentPollIdOrderByCreatedAtAsc(POLL_ID))
                .thenReturn(List.of(first, second));
        when(contentVoteRepository.findByContentCandidate_ContentPoll_Id(POLL_ID)).thenReturn(List.of(
                new ContentVote(first, HOST_MEMBER_ID, ContentPreference.PREFER),
                new ContentVote(first, MEMBER_ID, ContentPreference.DISLIKE),
                new ContentVote(second, HOST_MEMBER_ID, ContentPreference.AVAILABLE)
        ));

        // when
        ContentPollResultsResponse.Detail results =
                service.getResults(MEETING_ID, HOST_ID, ContentPollResultSort.SCORE);

        // then
        assertEquals(2, results.getJoinedCount());
        ContentPollResultsResponse.CandidateResult firstResult = results.getCandidates().get(0);
        assertEquals(40L, firstResult.getCandidateId());
        assertEquals(4, firstResult.getTotalScore());
        assertEquals(1, firstResult.getPreferCount());
        assertEquals(1, firstResult.getDislikeCount());
        assertEquals(0, firstResult.getNoResponseCount());
        assertEquals("host", firstResult.getCreatedByNickname());
        assertEquals(1, results.getCandidates().get(1).getNoResponseCount());

        ContentPollResultsResponse.MemberResult memberResult = results.getMembers().get(1);
        assertEquals(Arrays.asList(ContentPreference.DISLIKE, null), memberResult.getPreferences());
        assertEquals(1, memberResult.getResponseCount());
        assertEquals(true, results.getMembers().get(0).getHost());
    }

    @Test
    @DisplayName("마감 후 탈퇴한 사람의 표는 참여 인원/미응답 수/인원별 결과에 함께 포함되어 합계가 맞음")
    void getResultsKeepsWithdrawnVoter() {

        // given
        poll.close();
        ContentCandidate first = candidate(40L, poll, MEMBER_ID);
        when(contentCandidateRepository.findByContentPollIdOrderByCreatedAtAsc(POLL_ID))
                .thenReturn(List.of(first));
        when(contentVoteRepository.findByContentCandidate_ContentPoll_Id(POLL_ID)).thenReturn(List.of(
                new ContentVote(first, HOST_MEMBER_ID, ContentPreference.PREFER),
                new ContentVote(first, MEMBER_ID, ContentPreference.DISLIKE)
        ));
        member.leave();

        // when
        ContentPollResultsResponse.Detail results =
                service.getResults(MEETING_ID, HOST_ID, ContentPollResultSort.SCORE);

        // then
        ContentPollResultsResponse.CandidateResult result = results.getCandidates().get(0);
        assertEquals(2, results.getJoinedCount());
        assertEquals(2, result.getResponseCount());
        assertEquals(0, result.getNoResponseCount());
        assertEquals("member", result.getCreatedByNickname());
        assertEquals(2, results.getMembers().size());
    }

    @Test
    @DisplayName("마감된 투표판의 후보를 확정하고 모임 공유 잠금 후 투표판 잠금 순서로 조회")
    void confirm() {

        // given
        poll.close();
        ContentCandidate candidate = candidate(40L, poll, HOST_MEMBER_ID);
        when(contentCandidateRepository.findById(40L)).thenReturn(Optional.of(candidate));

        // when
        ContentPollResponse.Confirmed confirmed = service.confirm(MEETING_ID, HOST_ID,
                new ContentPollRequest.Confirm(40L));

        // then
        assertEquals(40L, confirmed.getConfirmedCandidateId());
        InOrder order = inOrder(meetingRepository, contentPollRepository);
        order.verify(meetingRepository).findByIdAndDeletedAtIsNullForShare(MEETING_ID);
        order.verify(contentPollRepository).findByMeetingIdForUpdate(MEETING_ID);
    }

    @Test
    @DisplayName("호스트가 아니면 후보를 확정할 수 없음")
    void confirmByNonHost() {

        // when / then
        assertThrows(MeetingAccessDeniedException.class, () ->
                service.confirm(MEETING_ID, 2L, new ContentPollRequest.Confirm(40L)));
    }

    @Test
    @DisplayName("이미 확정된 투표판은 다시 확정할 수 없음")
    void confirmTwice() {

        // given
        poll.close();
        poll.confirm(40L);

        // when / then
        assertThrows(ContentPollAlreadyConfirmedException.class, () ->
                service.confirm(MEETING_ID, HOST_ID, new ContentPollRequest.Confirm(41L)));
    }

}
