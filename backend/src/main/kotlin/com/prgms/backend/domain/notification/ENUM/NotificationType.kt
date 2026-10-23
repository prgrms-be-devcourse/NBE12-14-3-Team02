// 패키지명 정리(ENUM → enums)는 변환과 분리해 별도 PR로 진행한다.
@file:Suppress("ktlint:standard:package-name")

package com.prgms.backend.domain.notification.ENUM

// 각 상태별 알림
enum class NotificationType {
    SCHEDULE_CLOSED, // 일정 마감
    CONTENT_CLOSED, // 콘텐츠 마감
    SETTLEMENT_CLOSED, // 정산 마감
}
