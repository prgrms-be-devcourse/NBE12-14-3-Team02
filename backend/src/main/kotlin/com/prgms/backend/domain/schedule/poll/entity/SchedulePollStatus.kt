package com.prgms.backend.domain.schedule.poll.entity

enum class SchedulePollStatus(
    val description: String,
) {
    OPEN("투표 진행 중"),
    CLOSED("투표 마감"),
}
