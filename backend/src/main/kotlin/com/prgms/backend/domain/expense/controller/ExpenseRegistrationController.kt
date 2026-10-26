package com.prgms.backend.domain.expense.controller

import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest
import com.prgms.backend.domain.expense.dto.ExpenseResponse
import com.prgms.backend.domain.expense.service.ExpenseRegistrationService
import com.prgms.backend.global.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/api/meetings/{meetingId}/expenses")
class ExpenseRegistrationController(
    private val registrations: ExpenseRegistrationService,
) {
    @PostMapping(headers = ["Idempotency-Key"])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable meetingId: Long,
        principal: Principal?,
        @RequestHeader("Idempotency-Key") requestKey: String,
        @Valid @RequestBody request: ExpenseCreateRequest,
    ): ApiResponse<ExpenseResponse> =
        ApiResponse.success(201, registrations.create(meetingId, principal, request, requestKey))
}
