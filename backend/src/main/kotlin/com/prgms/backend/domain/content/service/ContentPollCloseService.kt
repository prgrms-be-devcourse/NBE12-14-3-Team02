package com.prgms.backend.domain.content.service

import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.integration.ContentPollClosedEvent
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.global.exception.custom.content.ContentPollNotFoundException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ContentPollCloseService(
    private val contentPollRepository: ContentPollRepository,
    private val meetingRepository: MeetingRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    // 투표판 1건당 트랜잭션 1개. 알림은 AFTER_COMMIT 리스너가 저장
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun closeOneIfExpired(pollId: Long) {
        // 투표판이 어느 Meeting 소속인지 확인
        val meetingId =
            contentPollRepository.findMeetingIdByPollId(pollId)
                ?: throw NoSuchElementException("No value present")

        // Meeting을 먼저 공유 잠금
        val meeting = meetingRepository.findByIdAndDeletedAtIsNullForShare(meetingId)

        // 삭제된 모임
        if (meeting == null) {
            return
        }

        // 이미 종료된 모임
        if (!meeting.isActive()) {
            return
        }

        val poll =
            contentPollRepository.findByIdForUpdate(pollId)
                ?: throw ContentPollNotFoundException(meetingId)

        val now = LocalDateTime.now()
        val deadline = checkNotNull(poll.deadline) { "마감 대상 투표판의 마감기한은 null일 수 없습니다." }

        // 잠금 이후 재검증 (그 사이 마감되었거나 마감기한이 연장된 경우)
        if (poll.status != ContentPollStatus.OPEN || deadline.isAfter(now)) {
            return
        }

        poll.close()

        eventPublisher.publishEvent(ContentPollClosedEvent(poll.meetingId, poll.id))
    }
}
