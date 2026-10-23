package com.prgms.backend.global.exception.custom.notification

import com.prgms.backend.global.exception.BusinessException

class NotificationAccessDeniedException(
    notificationId: Long?,
    userId: Long?,
) : BusinessException(403, "알림에 접근할 수 없습니다. notificationId = $notificationId, userId = $userId")
