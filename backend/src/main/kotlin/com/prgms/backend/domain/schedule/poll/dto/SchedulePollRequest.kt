package com.prgms.backend.domain.schedule.poll.dto

import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

class SchedulePollRequest private constructor() {
    data class Create(
        @field:NotNull(message = "마감 시간은 필수입니다.")
        @field:Future(message = "마감 시간은 현재보다 이후여야 합니다.")
        val deadline: LocalDateTime?,
    )

    data class UpdateDeadline(
        @field:NotNull(message = "마감 시간은 필수입니다.")
        @field:Future(message = "마감 시간은 현재보다 이후여야 합니다.")
        val deadline: LocalDateTime?,
    )
}
