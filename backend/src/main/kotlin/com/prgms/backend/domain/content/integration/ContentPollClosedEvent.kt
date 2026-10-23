package com.prgms.backend.domain.content.integration

data class ContentPollClosedEvent(
    val meetingId: Long,
    val pollId: Long,
)
