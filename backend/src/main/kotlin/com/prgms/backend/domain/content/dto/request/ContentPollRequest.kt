package com.prgms.backend.domain.content.dto.request

import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

class ContentPollRequest private constructor() {
    data class Create(
        @field:NotNull(message = "마감 시간을 입력해주세요.")
        @field:Future(message = "마감 시간이 잘못 입력되었습니다.")
        val deadline: LocalDateTime?,
    )

    data class UpdateDeadline(
        @field:NotNull(message = "마감시간을 입력해주세요.")
        @field:Future(message = "마감시간이 잘못입력되었습니다.")
        val deadline: LocalDateTime?,
    )

    data class Confirm(
        @field:NotNull(message = "후보 ID는 필수입니다.")
        val candidateId: Long?,
    )
}
