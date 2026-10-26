package com.prgms.backend.domain.schedule.candidate.dto

import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotNull
import java.time.LocalDate

class ScheduleCandidateRequest private constructor() {
    data class Create(
        @field:Future(message = "후보 날짜는 오늘보다 이후여야 합니다.")
        @field:NotNull(message = "날짜는 필수입니다")
        val candidateDate: LocalDate?,
    )

    data class Update(
        @field:Future(message = "후보 날짜는 오늘보다 이후여야 합니다.")
        @field:NotNull(message = "날짜는 필수입니다")
        val candidateDate: LocalDate?,
    )
}
