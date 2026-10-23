package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

class MeetingInvitationNotFoundException : BusinessException {
    constructor(inviteCode: String) : super(
        404,
        "유효하지 않은 초대 코드입니다. inviteCode = $inviteCode",
    )

    constructor(invitationId: Long) : super(
        404,
        "초대 정보를 찾을 수 없습니다. invitationId=$invitationId",
    )
}
