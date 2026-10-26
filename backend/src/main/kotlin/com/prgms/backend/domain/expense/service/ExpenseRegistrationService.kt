package com.prgms.backend.domain.expense.service

import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest
import com.prgms.backend.domain.expense.dto.ExpenseResponse
import com.prgms.backend.domain.expense.entity.ExpenseRegistration
import com.prgms.backend.domain.expense.repository.ExpenseRegistrationRepository
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.settlement.integration.MeetingAccessPort
import com.prgms.backend.global.exception.custom.expense.ExpenseRequestException
import jakarta.validation.Validator
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Principal
import java.util.HexFormat

@Service
class ExpenseRegistrationService(
    private val expenses: ExpenseService,
    private val access: MeetingAccessPort,
    private val expenseRepository: ExpenseRepository,
    private val registrations: ExpenseRegistrationRepository,
    private val validator: Validator,
) {
    @Transactional
    fun create(
        meetingId: Long,
        principal: Principal?,
        request: ExpenseCreateRequest?,
        requestKey: String?,
    ): ExpenseResponse {
        if (meetingId <= 0 || requestKey == null || !REQUEST_KEY_PATTERN.matches(requestKey)) {
            throw ExpenseRequestException(400, "모임과 등록 요청 번호를 확인해주세요.")
        }
        val context: MeetingAccessPort.Context = access.requireMember(meetingId, principal)
        if (request == null || validator.validate(request).isNotEmpty()) {
            throw ExpenseRequestException(400, "지출 입력값을 확인해주세요.")
        }
        // 기존 toLowerCase(Locale.ROOT)와 같은 기준입니다.
        val key = requestKey.lowercase()
        val fingerprint = fingerprint(request)
        val registration = registrations.findByMeetingIdAndMemberIdAndRequestKey(meetingId, context.memberId, key)
        if (registration != null) {
            if (registration.fingerprint != fingerprint) {
                throw ExpenseRequestException(409, "같은 등록 요청의 내용이 달라졌습니다. 기존 지출을 확인해주세요.")
            }
            val saved =
                expenseRepository.findByIdOrNull(registration.expenseId)
                    ?: throw ExpenseRequestException(410, "이미 등록 후 삭제된 지출입니다. 목록을 확인해주세요.")
            return ExpenseResponse.from(saved)
        }
        // 지출 서비스와 동일한 트랜잭션·모임 잠금 안에서 요청 번호까지 함께 저장합니다.
        val saved = expenses.create(meetingId, principal, request)
        registrations.save(ExpenseRegistration(meetingId, context.memberId, key, fingerprint, saved.id))
        return saved
    }

    // 저장된 지문과 비교하므로 기록 순서와 형식을 기존과 같게 유지합니다.
    private fun fingerprint(request: ExpenseCreateRequest): String =
        try {
            val amount = checkNotNull(request.amount) { "검증을 통과한 요청의 지출 금액은 null일 수 없습니다." }
            val splitMode = checkNotNull(request.splitMode) { "검증을 통과한 요청의 분담 방식은 null일 수 없습니다." }
            val participants = checkNotNull(request.participants) { "검증을 통과한 요청의 참여자 목록은 null일 수 없습니다." }
            val bytes = ByteArrayOutputStream()
            val output = DataOutputStream(bytes)
            writeValue(output, request.title)
            writeValue(output, amount.stripTrailingZeros().toPlainString())
            writeValue(output, request.memo)
            writeValue(output, splitMode.name)
            writeValue(output, request.roundingUnit)
            writeValue(output, request.remainderMemberId)
            output.writeBoolean(request.randomRemainder)
            for (participant in participants) {
                checkNotNull(participant) { "검증을 통과한 요청의 참여자는 null일 수 없습니다." }
                output.writeLong(checkNotNull(participant.memberId) { "검증을 통과한 요청의 참여자 ID는 null일 수 없습니다." })
                writeValue(output, participant.amount?.stripTrailingZeros()?.toPlainString())
            }
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
        } catch (e: IOException) {
            throw IllegalStateException("등록 요청 확인에 실패했습니다.", e)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("등록 요청 확인에 실패했습니다.", e)
        }

    private fun writeValue(
        output: DataOutputStream,
        value: Any?,
    ) {
        output.writeBoolean(value != null)
        if (value != null) output.writeUTF(value.toString())
    }

    companion object {
        private val REQUEST_KEY_PATTERN = Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
    }
}
