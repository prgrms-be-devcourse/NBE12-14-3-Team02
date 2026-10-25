package com.prgms.backend.domain.schedule.poll.service

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import com.prgms.backend.domain.schedule.poll.event.SchedulePollClosedEvent
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class SchedulePollCloseService(
    private val schedulePollRepository: SchedulePollRepository,
    private val meetingRepository: MeetingRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    // 첫 조회 이후 가입한 회원도 마감 시점의 커밋된 참여자에 포함한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun closeOneIfExpired(pollId: Long) {
        val meetingId = schedulePollRepository.findMeetingIdByPollId(pollId) ?: return
        // Meeting → Poll 순서로 잠금을 획득한다.
        val meeting = meetingRepository.findByIdAndDeletedAtIsNullForShare(meetingId) ?: return
        if (!meeting.isActive()) return
        val poll = schedulePollRepository.findByIdForUpdate(pollId) ?: return
        val now = LocalDateTime.now()
        if (poll.status != SchedulePollStatus.OPEN || poll.deadline.isAfter(now)) return
        poll.close()
        eventPublisher.publishEvent(SchedulePollClosedEvent(poll.id, meetingId))
    }
}
