package com.prgms.backend.domain.schedule.vote.entity

enum class SchedulePreference(
    val score: Int,
) {
    PREFER(3),
    AVAILABLE(2),
    DISLIKE(1),
    IMPOSSIBLE(0),
}
