package com.prgms.backend.domain.expense.controller

import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest
import com.prgms.backend.domain.expense.dto.ExpenseListResponse
import com.prgms.backend.domain.expense.dto.ExpenseResponse
import com.prgms.backend.domain.expense.service.ExpenseService
import com.prgms.backend.global.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/api/meetings/{meetingId}/expenses")
class ExpenseController(
    private val service: ExpenseService,
) {
    @GetMapping
    fun list(
        @PathVariable meetingId: Long,
        principal: Principal?,
    ): ApiResponse<ExpenseListResponse> = ApiResponse.success(200, service.list(meetingId, principal))

    @GetMapping("/{expenseId}")
    fun get(
        @PathVariable meetingId: Long,
        @PathVariable expenseId: Long,
        principal: Principal?,
    ): ApiResponse<ExpenseResponse> = ApiResponse.success(200, service.get(meetingId, expenseId, principal))

    @PutMapping("/{expenseId}")
    fun update(
        @PathVariable meetingId: Long,
        @PathVariable expenseId: Long,
        principal: Principal?,
        @Valid @RequestBody request: ExpenseCreateRequest,
    ): ApiResponse<ExpenseResponse> = ApiResponse.success(200, service.update(meetingId, expenseId, principal, request))

    @DeleteMapping("/{expenseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable meetingId: Long,
        @PathVariable expenseId: Long,
        principal: Principal?,
    ) {
        service.delete(meetingId, expenseId, principal)
    }

    // Idempotency-Key 헤더가 있는 등록 요청은 ExpenseRegistrationController가 처리합니다.
    @PostMapping(headers = ["!Idempotency-Key"])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable meetingId: Long,
        principal: Principal?,
        @Valid @RequestBody request: ExpenseCreateRequest,
    ): ApiResponse<ExpenseResponse> = ApiResponse.success(201, service.create(meetingId, principal, request))
}
