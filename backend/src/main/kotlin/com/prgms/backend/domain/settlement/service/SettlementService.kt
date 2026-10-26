package com.prgms.backend.domain.settlement.service

import com.prgms.backend.domain.expense.entity.Expense
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.settlement.dto.SettlementPreviewResponse
import com.prgms.backend.domain.settlement.dto.SettlementResponse
import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.entity.SettlementAccountSnapshot
import com.prgms.backend.domain.settlement.entity.SettlementBalance
import com.prgms.backend.domain.settlement.entity.SettlementTransfer
import com.prgms.backend.domain.settlement.enums.SettlementStatus
import com.prgms.backend.domain.settlement.integration.MeetingAccessPort
import com.prgms.backend.domain.settlement.integration.SettlementClosedEvent
import com.prgms.backend.domain.settlement.repository.SettlementAccountRepository
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.global.exception.custom.settlement.SettlementRequestException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.Principal

@Service
class SettlementService(
    private val meetingAccess: MeetingAccessPort,
    private val expenseRepository: ExpenseRepository,
    private val settlementRepository: SettlementRepository,
    private val calculator: SettlementCalculator,
    private val eventPublisher: ApplicationEventPublisher,
    private val accountRepository: SettlementAccountRepository,
    private val memberRepository: MeetingMemberRepository,
) {
    @Transactional
    fun preview(
        meetingId: Long,
        principal: Principal?,
    ): SettlementPreviewResponse {
        // 지출 변경과 동일한 잠금으로 일관된 예상 결과를 계산하되 저장하지 않습니다.
        val context: MeetingAccessPort.Context = meetingAccess.requireMember(meetingId, principal)
        if (!context.meetingOpen) throw SettlementRequestException(409, "종료된 모임입니다.")
        settlementRepository.findByMeetingId(meetingId)?.checkOpen()
        val expenses: List<Expense> = expenseRepository.findByMeetingIdOrderByIdAsc(meetingId)
        val result = calculator.calculate(meetingId, expenses, context.memberIds)
        val registered = accountRepository.findByMeetingId(meetingId).map { it.memberId }.toSet()
        val missing = missingRecipients(result, registered)
        val names = memberRepository.findAllById(missing).associate { it.id to it.user.nickname }
        return SettlementPreviewResponse(
            balances = result.balances,
            transfers = result.transfers,
            missingAccounts = missing.map { SettlementPreviewResponse.MissingAccount(it, names[it] ?: "모임원 #$it") },
        )
    }

    @Transactional
    fun confirm(
        meetingId: Long,
        principal: Principal?,
    ): SettlementResponse {
        // 모임 잠금은 트랜잭션 종료까지 유지, 확정 중 새 지출 추가x
        val context: MeetingAccessPort.Context = meetingAccess.requireMember(meetingId, principal)
        if (!context.leader) {
            throw SettlementRequestException(403, "모임장만 정산을 확정할 수 있습니다.")
        }
        if (!context.meetingOpen) {
            throw SettlementRequestException(409, "종료된 모임은 정산을 확정할 수 없습니다.")
        }

        // 지출 상시 등록,모임장의 정산 확정 요청을 통해 정산 데이터 생성
        val settlement = settlementRepository.findByMeetingId(meetingId) ?: Settlement(meetingId)
        settlement.checkOpen()

        val expenses: List<Expense> = expenseRepository.findByMeetingIdOrderByIdAsc(meetingId)
        val result = calculator.calculate(meetingId, expenses, context.memberIds)
        val accounts = accountRepository.findByMeetingId(meetingId).associateBy { it.memberId }
        val recipients =
            result.transfers
                .map { it.recipientId }
                .distinct()
                .sorted()
        val missing = missingRecipients(result, accounts.keys)
        if (missing.isNotEmpty()) {
            throw SettlementRequestException(409, "계좌를 등록하지 않은 정산 수취인이 있습니다. 모임원 ID: $missing")
        }
        val snapshots = recipients.map { SettlementAccountSnapshot(accounts.getValue(it)) }
        val balances = result.balances.map { SettlementBalance(it.memberId, it.paidAmount, it.shareAmount) }
        val transfers = result.transfers.map { SettlementTransfer(it.senderId, it.recipientId, it.amount) }

        // 결과 저장과 상태 변경은 함께 성공 또는 취소
        settlement.saveResult(balances, transfers)
        settlement.saveAccounts(snapshots)
        settlement.close(context.memberId)
        val saved = settlementRepository.save(settlement)
        eventPublisher.publishEvent(SettlementClosedEvent(meetingId, saved.id, saved.closedAt))
        return SettlementResponse.forMember(saved, context.memberId, false)
    }

    private fun missingRecipients(
        result: SettlementCalculator.Result,
        registered: Set<Long>,
    ): List<Long> =
        result.transfers
            .map { it.recipientId }
            .distinct()
            .sorted()
            .filter { it !in registered }

    @Transactional(readOnly = true)
    fun getResult(
        meetingId: Long,
        principal: Principal?,
    ): SettlementResponse {
        val context: MeetingAccessPort.Context = meetingAccess.requireMemberForRead(meetingId, principal)
        val settlement =
            settlementRepository
                .findByMeetingId(meetingId)
                ?.takeIf { it.status == SettlementStatus.CLOSED }
                ?: throw SettlementRequestException(404, "아직 확정된 정산이 없습니다.")
        return SettlementResponse.forMember(settlement, context.memberId, false)
    }
}
