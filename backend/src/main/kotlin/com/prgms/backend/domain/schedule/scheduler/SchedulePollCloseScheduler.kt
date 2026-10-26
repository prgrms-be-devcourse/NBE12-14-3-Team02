package com.prgms.backend.domain.schedule.scheduler

import com.prgms.backend.domain.meeting.enums.MeetingStatus
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.schedule.poll.service.SchedulePollCloseService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class SchedulePollCloseScheduler(
    private val schedulePollRepository: SchedulePollRepository,
    private val schedulePollCloseService: SchedulePollCloseService,
) {
    @Scheduled(fixedDelay = 30_000)
    fun closeExpired() {
        // 종료·삭제된 모임의 투표는 자동 마감 대상에서 제외한다.
        val expiredPollIds =
            schedulePollRepository.findExpiredIds(
                SchedulePollStatus.OPEN,
                MeetingStatus.ACTIVE,
                LocalDateTime.now(),
            )
        expiredPollIds.forEach { pollId ->
            try {
                schedulePollCloseService.closeOneIfExpired(pollId)
            } catch (e: Exception) {
                log.error("일정 투표 자동 마감 실패. pollId={}", pollId, e)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SchedulePollCloseScheduler::class.java)
    }
}
