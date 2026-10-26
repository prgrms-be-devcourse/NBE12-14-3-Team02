package com.prgms.backend.domain.schedule.vote.dto

import com.prgms.backend.domain.schedule.vote.entity.SchedulePreference
import jakarta.validation.constraints.NotNull

class ScheduleVoteRequest private constructor() {
    data class Submit(
        @field:NotNull(message = "선호도는 필수입니다.")
        val preference: SchedulePreference?,
    )
}
