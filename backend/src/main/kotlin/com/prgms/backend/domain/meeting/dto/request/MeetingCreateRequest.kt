package com.prgms.backend.domain.meeting.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class MeetingCreateRequest(
    @field:NotBlank(message = "모임 이름은 필수입니다.")
    @field:Size(max = 100, message = "모임 이름은 100자 이하로 입력해주세요.")
    val name: String?,
    @field:Size(max = 500, message = "모임 설명은 500자 이하로 입력해주세요.")
    val description: String?,
)
