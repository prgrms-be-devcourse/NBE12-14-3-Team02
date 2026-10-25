package com.prgms.backend.domain.schedule.poll.service;

import com.prgms.backend.domain.meeting.entity.Meeting;
import com.prgms.backend.domain.meeting.entity.MeetingMember;
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository;
import com.prgms.backend.domain.meeting.repository.MeetingRepository;
import com.prgms.backend.domain.schedule.poll.dto.SchedulePollRequest;
import com.prgms.backend.domain.schedule.poll.dto.SchedulePollResponse;
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll;
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository;
import com.prgms.backend.global.exception.custom.meeting.MeetingHostRequiredException;
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberAlreadyLeftException;
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberNotFoundException;
import com.prgms.backend.global.exception.custom.meeting.MeetingNotActiveException;
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException;
import com.prgms.backend.global.exception.custom.schedule.SchedulePollAlreadyExistsException;
import com.prgms.backend.global.exception.custom.schedule.SchedulePollNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SchedulePollService {
    private final SchedulePollRepository schedulePollRepository;
    private final MeetingRepository meetingRepository;
    private final MeetingMemberRepository meetingMemberRepository;

    @Transactional
    public SchedulePollResponse.Created createPoll(
            Long meetingId,
            Long userId,
            SchedulePollRequest.Create request
    ) {
        Meeting meeting = getMeetingWithExclusiveLock(meetingId);

        validateHost(meeting, userId);
        validateActiveMeeting(meeting);
        validateNoExistingPoll(meetingId);

        SchedulePoll schedulePoll = SchedulePoll.create(meeting,request.getDeadline());
        SchedulePoll savedPoll = schedulePollRepository.save(schedulePoll);

       return SchedulePollResponse.Created.from(savedPoll);
    }

    @Transactional(readOnly = true)
    public SchedulePollResponse.Detail getPoll(Long meetingId, Long userId) {

        validateMeetingExists(meetingId);
        validateJoinedMember(meetingId, userId);

        SchedulePoll schedulePoll = getPollWithCandidates(meetingId);

        return SchedulePollResponse.Detail.from(schedulePoll);
    }

    @Transactional
    public SchedulePollResponse.DeadlineUpdate updateDeadline(
            Long meetingId,
            Long userId,
            SchedulePollRequest.UpdateDeadline request
            ) {

        Meeting meeting = getMeetingWithSharedLock(meetingId);

        validateHost(meeting,userId);
        validateActiveMeeting(meeting);

        SchedulePoll schedulePoll = getPollWithExclusiveLock(meetingId);

        schedulePoll.updateDeadline(request.getDeadline(),LocalDateTime.now());

        return SchedulePollResponse.DeadlineUpdate.from(
                schedulePoll
        );
    }

    private void validateActiveMeeting(Meeting meeting){
        if (!meeting.isActive()) {
            throw new MeetingNotActiveException(meeting.getId());
        }
    }

    private void validateHost(
            Meeting meeting,
            Long userId
    ) {
        if (!meeting.isHost(userId)) {
            throw new MeetingHostRequiredException();
        }
    }

    private Meeting getMeetingWithExclusiveLock(Long meetingId){
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNullForUpdate(meetingId);
        if (meeting == null) {
            throw new MeetingNotFoundException(meetingId);
        }
        return meeting;
    }

    private Meeting getMeetingWithSharedLock(Long meetingId){
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNullForShare(meetingId);
        if (meeting == null) {
            throw new MeetingNotFoundException(meetingId);
        }
        return meeting;
    }

    private void validateNoExistingPoll(Long meetingId){
        if(schedulePollRepository.existsByMeetingId(meetingId)){
            throw new SchedulePollAlreadyExistsException(meetingId);
        }
    }

    private void validateJoinedMember(Long meetingId, Long userId){
        MeetingMember meetingMember = meetingMemberRepository.findByMeetingIdAndUserId(meetingId, userId);
        if (meetingMember == null) {
            throw new MeetingMemberNotFoundException(meetingId, userId);
        }
        if(!meetingMember.isJoined()){
            throw new MeetingMemberAlreadyLeftException(meetingId, userId);
        }
    }

    private void validateMeetingExists(Long meetingId){
        if (meetingRepository.findByIdAndDeletedAtIsNull(meetingId) == null) {
            throw new MeetingNotFoundException(meetingId);
        }
    }

    private SchedulePoll getPollWithCandidates(Long meetingId){
        return java.util.Optional.ofNullable(schedulePollRepository.findByMeetingIdWithCandidates(meetingId))
                .orElseThrow(
                        () -> new SchedulePollNotFoundException(
                                meetingId
                        )
                );
    }

    private SchedulePoll getPollWithExclusiveLock(Long meetingId){
        return java.util.Optional.ofNullable(schedulePollRepository.findByMeetingIdForUpdate(meetingId))
                .orElseThrow(
                        () -> new SchedulePollNotFoundException(meetingId)
                );
    }
}
