package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

class MeetingMemberNotFoundException : BusinessException {
    constructor(meetingMemberId: Long) : super(
        404,
        "모임 멤버가 존재하지 않습니다. meetingMemberId = $meetingMemberId",
    )

    constructor(meetingId: Long, userId: Long) : super(
        404,
        "해당 모임에 사용자가 존재하지 않습니다. meetingId = $meetingId, userId = $userId",
    )
}
