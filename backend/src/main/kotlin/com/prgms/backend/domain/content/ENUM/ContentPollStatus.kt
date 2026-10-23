// 패키지명 정리(ENUM → enums)는 변환과 분리해 별도 PR로 진행한다.
@file:Suppress("ktlint:standard:package-name")

package com.prgms.backend.domain.content.ENUM

enum class ContentPollStatus {
    OPEN, // 투표판이 열려있음
    CLOSED,
}
