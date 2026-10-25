package com.prgms.backend.domain.schedule.poll.service;

import com.prgms.backend.domain.meeting.entity.Meeting;
import com.prgms.backend.domain.meeting.entity.MeetingMember;
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus;
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository;
import com.prgms.backend.domain.meeting.repository.MeetingRepository;
import com.prgms.backend.domain.schedule.candidate.entity.ScheduleCandidate;
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll;
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository;
import com.prgms.backend.domain.schedule.vote.entity.SchedulePreference;
import com.prgms.backend.domain.schedule.vote.repository.ScheduleVoteRepository;
import com.prgms.backend.global.exception.custom.schedule.SchedulePollNotClosedException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.candidate;
import static com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.meeting;
import static com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.member;
import static com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.poll;
import static com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.user;
import static com.prgms.backend.domain.schedule.support.ScheduleTestFixtures.vote;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScheduleResultServiceTest {
    private final SchedulePollRepository polls = mock(SchedulePollRepository.class);
    private final MeetingMemberRepository members = mock(MeetingMemberRepository.class);
    private final ScheduleVoteRepository votes = mock(ScheduleVoteRepository.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final ScheduleResultService service =
            new ScheduleResultService(polls, members, votes, meetings);

    private SchedulePoll poll;
    private MeetingMember host;

    @BeforeEach
    void setUp() {
        Meeting meeting = meeting(10L, user(1L, "host"));
        poll = poll(20L, meeting, LocalDateTime.now().plusDays(3));
        host = member(30L, meeting, meeting.getHost());
        when(meetings.findByIdAndDeletedAtIsNull(10L)).thenReturn(meeting);
        when(members.findByMeetingIdAndUserId(10L, 1L)).thenReturn(host);
        when(polls.findByMeetingId(10L)).thenReturn(poll);
    }

    @Test
    @DisplayName("마감 결과는 총점·공동 순위·0점 응답·미응답을 구분하고 탈퇴자 표를 제외한다")
    void rankClosedPoll() {
        poll.close();
        LocalDate firstDate = LocalDate.now().plusDays(1);
        ScheduleCandidate first = candidate(40L, poll, firstDate);
        ScheduleCandidate second = candidate(41L, poll, firstDate.plusDays(1));
        ScheduleCandidate unanswered = candidate(42L, poll, firstDate.plusDays(2));
        Meeting meeting = poll.getMeeting();
        MeetingMember secondMember = member(31L, meeting, user(2L, "second"));
        MeetingMember thirdMember = member(32L, meeting, user(3L, "third"));
        MeetingMember leftMember = member(33L, meeting, user(4L, "left"));
        leftMember.leave();

        when(members.findAllByMeetingIdAndStatus(10L, MeetingMemberStatus.JOINED))
                .thenReturn(List.of(host, secondMember, thirdMember));
        when(votes.findAllByScheduleCandidateSchedulePollId(20L)).thenReturn(List.of(
                vote(first, host, SchedulePreference.PREFER),
                vote(first, secondMember, SchedulePreference.IMPOSSIBLE),
                vote(second, host, SchedulePreference.AVAILABLE),
                vote(second, secondMember, SchedulePreference.DISLIKE),
                vote(unanswered, leftMember, SchedulePreference.PREFER)
        ));

        var result = service.getResults(10L, 1L);

        assertEquals(List.of(40L, 41L, 42L), result.getCandidateRanks().stream()
                .map(rank -> rank.getCandidateId()).toList());
        assertEquals(List.of(1, 1, 3), result.getCandidateRanks().stream()
                .map(rank -> rank.getRank()).toList());
        assertEquals(List.of(3, 3, 0), result.getCandidateRanks().stream()
                .map(rank -> rank.getTotalScore()).toList());
        assertEquals(2, result.getCandidateRanks().getFirst().getResponseCount());
        assertEquals(1, result.getCandidateRanks().getFirst().getNonResponseCount());
        assertEquals(1, result.getCandidateRanks().getFirst().getPreferenceCounts().getImpossible());
        assertEquals(0, result.getCandidateRanks().getLast().getResponseCount());
        assertEquals(3, result.getCandidateRanks().getLast().getNonResponseCount());

        assertEquals(3, result.getParticipantResponses().size());
        assertEquals(SchedulePreference.PREFER,
                result.getParticipantResponses().getFirst().getAnswers().getFirst().getPreference());
        assertEquals(SchedulePreference.IMPOSSIBLE,
                result.getParticipantResponses().get(1).getAnswers().getFirst().getPreference());
        assertNull(result.getParticipantResponses().getLast().getAnswers().getFirst().getPreference());
    }

    @Test
    @DisplayName("열린 투표에서는 순위와 참여자별 결과를 공개하지 않는다")
    void rejectResultsBeforeClose() {
        assertThrows(SchedulePollNotClosedException.class,
                () -> service.getResults(10L, 1L));
        verifyNoInteractions(votes);
    }
}
