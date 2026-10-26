package com.prgms.backend.domain.expense.service

import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest
import com.prgms.backend.domain.expense.dto.ExpenseListResponse
import com.prgms.backend.domain.expense.dto.ExpenseResponse
import com.prgms.backend.domain.expense.entity.Expense
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.expense.repository.getMeetingExpense
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.settlement.enums.SettlementStatus
import com.prgms.backend.domain.settlement.integration.MeetingAccessPort
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.global.exception.custom.expense.ExpenseRequestException
import jakarta.validation.Validator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.Principal

@Service
class ExpenseService(
    private val expenseRepository: ExpenseRepository,
    private val splitCalculator: ExpenseSplitCalculator,
    private val meetingAccess: MeetingAccessPort,
    private val settlementRepository: SettlementRepository,
    private val memberRepository: MeetingMemberRepository,
    private val validator: Validator,
    private val receiptStorage: ReceiptStorage,
) {
    @Transactional(readOnly = true)
    fun list(
        meetingId: Long,
        principal: Principal?,
    ): ExpenseListResponse {
        val context: MeetingAccessPort.Context = meetingAccess.requireMemberForRead(meetingId, principal)
        val closed = settlementRepository.findByMeetingId(meetingId)?.status == SettlementStatus.CLOSED
        val expenses = expenseRepository.findByMeetingIdOrderByIdAsc(meetingId).map(ExpenseResponse::from)
        return ExpenseListResponse(
            currentMemberId = context.memberId,
            leader = context.leader,
            editable = context.meetingOpen && !closed,
            expenses = expenses,
        )
    }

    @Transactional(readOnly = true)
    fun get(
        meetingId: Long,
        expenseId: Long,
        principal: Principal?,
    ): ExpenseResponse {
        meetingAccess.requireMemberForRead(meetingId, principal)
        return ExpenseResponse.from(expenseRepository.getMeetingExpense(meetingId, expenseId))
    }

    @Transactional
    fun update(
        meetingId: Long,
        expenseId: Long,
        principal: Principal?,
        request: ExpenseCreateRequest?,
    ): ExpenseResponse {
        // 등록·수정·삭제·확정 모두 같은 모임 잠금으로 처리 -> 순서보장
        val context: MeetingAccessPort.Context = meetingAccess.requireMember(meetingId, principal)
        checkRegistrationAllowed(meetingId, context)
        val expense = expenseRepository.getMeetingExpense(meetingId, expenseId)
        checkOwner(expense, context.memberId)
        val validRequest = validateRequest(request)
        val result = splitCalculator.calculateResult(validRequest)
        // 탈퇴자는 수정 불가
        checkParticipants(meetingId, result.shares.keys, expense.getParticipantIds())
        expense.update(
            title = titleOf(validRequest),
            amount = amountOf(validRequest),
            memo = validRequest.memo,
            shares = result.shares,
            splitMode = splitModeOf(validRequest).name,
            roundingUnit = getRoundingUnit(validRequest),
            remainderMemberId = result.remainderMemberId,
        )
        return ExpenseResponse.from(expense)
    }

    @Transactional
    fun delete(
        meetingId: Long,
        expenseId: Long,
        principal: Principal?,
    ) {
        val context: MeetingAccessPort.Context = meetingAccess.requireMember(meetingId, principal)
        checkRegistrationAllowed(meetingId, context)
        val expense = expenseRepository.getMeetingExpense(meetingId, expenseId)
        checkOwner(expense, context.memberId)
        expenseRepository.delete(expense)
        receiptStorage.deleteAfterCommit(expense.receiptKey)
    }

    private fun checkOwner(
        expense: Expense,
        memberId: Long,
    ) {
        if (expense.payerMemberId != memberId) {
            throw ExpenseRequestException(403, "본인이 등록한 지출만 수정·삭제할 수 있습니다.")
        }
    }

    @Transactional
    fun create(
        meetingId: Long,
        principal: Principal?,
        request: ExpenseCreateRequest?,
    ): ExpenseResponse {
        if (meetingId <= 0) {
            throw ExpenseRequestException(400, "모임 ID를 확인해주세요.")
        }
        // 정산 확정과 같은 모임 잠금을 사용, 먼저 시작한 작업이 끝난 뒤 다음 작업을 처리
        val context: MeetingAccessPort.Context = meetingAccess.requireMember(meetingId, principal)
        checkRegistrationAllowed(meetingId, context)
        val validRequest = validateRequest(request)

        val splitResult = splitCalculator.calculateResult(validRequest)
        val shares = splitResult.shares
        checkParticipants(meetingId, shares.keys, emptySet())

        val expense =
            Expense.register(
                meetingId = meetingId,
                payerMemberId = context.memberId,
                title = titleOf(validRequest),
                amount = amountOf(validRequest),
                memo = validRequest.memo,
                shares = shares,
                splitMode = splitModeOf(validRequest).name,
                roundingUnit = getRoundingUnit(validRequest),
                remainderMemberId = splitResult.remainderMemberId,
            )
        return ExpenseResponse.from(expenseRepository.save(expense))
    }

    private fun checkRegistrationAllowed(
        meetingId: Long,
        context: MeetingAccessPort.Context,
    ) {
        // 종료된 모임에 지출 등록 불가
        if (!context.meetingOpen) {
            throw ExpenseRequestException(409, "종료된 모임의 지출은 변경할 수 없습니다.")
        }

        // 정산 데이터가 없거나 마감 안했으면 등록 가능(open상태), 확정 누르면 closed로 등록 안됨
        settlementRepository.findByMeetingId(meetingId)?.checkOpen()
    }

    private fun validateRequest(request: ExpenseCreateRequest?): ExpenseCreateRequest {
        if (request == null || validator.validate(request).isNotEmpty()) {
            throw ExpenseRequestException(400, "지출 입력값을 확인해주세요.")
        }
        return request
    }

    // 모임에 참여했다가 탈퇴한 회원은 정산에 참여 x,
    private fun checkParticipants(
        meetingId: Long,
        participantIds: Set<Long>,
        existingParticipantIds: Set<Long>,
    ) {
        val joinedMemberIds =
            existingParticipantIds +
                memberRepository.findAllByMeetingIdAndStatus(meetingId, MeetingMemberStatus.JOINED).map { it.id }

        if (!joinedMemberIds.containsAll(participantIds)) {
            throw ExpenseRequestException(400, "새 부담자는 현재 모임에 참여 중인 모임원만 지정할 수 있습니다.")
        }
    }

    private fun getRoundingUnit(request: ExpenseCreateRequest): Int? =
        if (request.splitMode != ExpenseCreateRequest.SplitMode.EQUAL) null else request.roundingUnit ?: 1

    // 검증을 통과한 요청에서 지출 등록·수정에 필요한 필수 값을 꺼냅니다.
    private fun titleOf(request: ExpenseCreateRequest): String =
        checkNotNull(request.title) { "검증을 통과한 요청의 제목은 null일 수 없습니다." }

    private fun amountOf(request: ExpenseCreateRequest): Long =
        checkNotNull(request.amount) { "검증을 통과한 요청의 지출 금액은 null일 수 없습니다." }.longValueExact()

    private fun splitModeOf(request: ExpenseCreateRequest): ExpenseCreateRequest.SplitMode =
        checkNotNull(request.splitMode) { "검증을 통과한 요청의 분담 방식은 null일 수 없습니다." }
}
