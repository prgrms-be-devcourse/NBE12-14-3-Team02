package com.prgms.backend.domain.content.integration

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class ContentPollClosedEventListener(
    private val contentNotificationWriter: ContentNotificationWriter,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onClosed(event: ContentPollClosedEvent) {
        try {
            contentNotificationWriter.save(event.meetingId)
        } catch (e: RuntimeException) {
            log.error("콘텐츠 마감 알림 저장 실패: meetingId={}, pollId={}", event.meetingId, event.pollId, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ContentPollClosedEventListener::class.java)
    }
}
