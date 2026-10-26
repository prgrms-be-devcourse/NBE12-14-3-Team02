package com.prgms.backend.domain.schedule.poll.event

data class SchedulePollClosedEvent(
    val pollId: Long,
    val meetingId: Long,
)
