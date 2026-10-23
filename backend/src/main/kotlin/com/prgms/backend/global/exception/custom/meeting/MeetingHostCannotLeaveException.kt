package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

// Java 호출부의 기존 생성자 시그니처를 유지한다.
@Suppress("UNUSED_PARAMETER")
class MeetingHostCannotLeaveException(
    meetingId: Long,
    userId: Long,
) : BusinessException(409, "진행 중인 모임의 모임장은 탈퇴할 수 없습니다.")
