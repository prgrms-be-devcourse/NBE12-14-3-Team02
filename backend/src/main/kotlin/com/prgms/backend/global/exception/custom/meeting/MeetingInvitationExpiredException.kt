package com.prgms.backend.global.exception.custom.meeting

import com.prgms.backend.global.exception.BusinessException

class MeetingInvitationExpiredException : BusinessException(410, "만료된 초대입니다.")
