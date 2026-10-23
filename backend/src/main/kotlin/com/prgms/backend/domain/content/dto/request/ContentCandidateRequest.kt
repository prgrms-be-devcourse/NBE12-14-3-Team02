package com.prgms.backend.domain.content.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

class ContentCandidateRequest private constructor() {
    data class Create(
        @field:NotBlank(message = "제목은 필수입니다.")
        @field:Size(max = 100, message = "제목은 100자 이하여야 합니다.")
        val title: String?,
        @field:Size(max = 500, message = "설명은 500자 이하여야 합니다.")
        val description: String?,
    )

    data class Update(
        @field:NotBlank(message = "제목은 필수입니다.")
        @field:Size(max = 100, message = "제목은 100자 이하여야 합니다.")
        val title: String?,
        @field:Size(max = 500, message = "설명은 500자 이하여야 합니다.")
        val description: String?,
    )
}
