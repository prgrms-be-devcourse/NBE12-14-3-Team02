package com.prgms.backend.domain.settlement.controller

import com.prgms.backend.domain.settlement.dto.AccountRequest
import com.prgms.backend.domain.settlement.dto.AccountResponse
import com.prgms.backend.domain.settlement.dto.SettlementPreviewResponse
import com.prgms.backend.domain.settlement.dto.SettlementResponse
import com.prgms.backend.domain.settlement.service.SettlementAccountService
import com.prgms.backend.domain.settlement.service.SettlementService
import com.prgms.backend.global.ApiResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/api/meetings/{meetingId}/settlement")
class SettlementController(
    private val settlementService: SettlementService,
    private val accountService: SettlementAccountService,
) {
    @GetMapping("/preview")
    fun preview(
        @PathVariable meetingId: Long,
        principal: Principal?,
    ): ApiResponse<SettlementPreviewResponse> =
        ApiResponse.success(200, settlementService.preview(meetingId, principal))

    // 등록된 계좌가 없으면 data가 null인 응답을 반환합니다.
    @GetMapping("/account")
    fun account(
        @PathVariable meetingId: Long,
        principal: Principal?,
    ): ApiResponse<AccountResponse> = ApiResponse.success(200, accountService.get(meetingId, principal))

    @PutMapping("/account")
    fun saveAccount(
        @PathVariable meetingId: Long,
        principal: Principal?,
        @Valid @RequestBody request: AccountRequest,
    ): ApiResponse<AccountResponse> = ApiResponse.success(200, accountService.save(meetingId, principal, request))

    // 모임장의 정산 확정 버튼에서 호출합니다. 사용자 ID는 요청으로 받지 않습니다.
    @PostMapping("/confirm")
    fun confirm(
        @PathVariable meetingId: Long,
        principal: Principal?,
    ): ApiResponse<SettlementResponse> = ApiResponse.success(200, settlementService.confirm(meetingId, principal))

    @GetMapping
    fun getResult(
        @PathVariable meetingId: Long,
        principal: Principal?,
    ): ApiResponse<SettlementResponse> = ApiResponse.success(200, settlementService.getResult(meetingId, principal))
}
