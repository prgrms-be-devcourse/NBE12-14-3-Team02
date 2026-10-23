package com.prgms.backend.domain.content.dto.request

import com.prgms.backend.domain.content.enums.ContentPreference
import jakarta.validation.constraints.NotNull

class ContentVoteRequest private constructor() {
    data class Submit(
        @field:NotNull(message = "후보 ID는 필수입니다.")
        val candidateId: Long?,
        @field:NotNull(message = "선호도는 필수입니다.")
        val preference: ContentPreference?,
    )
}
