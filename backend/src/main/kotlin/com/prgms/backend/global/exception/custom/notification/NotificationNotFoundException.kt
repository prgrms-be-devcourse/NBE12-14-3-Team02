package com.prgms.backend.global.exception.custom.notification

import com.prgms.backend.global.exception.BusinessException

class NotificationNotFoundException(
    notificationId: Long?,
) : BusinessException(404, "알림이 존재하지 않습니다. notificationId = $notificationId")
