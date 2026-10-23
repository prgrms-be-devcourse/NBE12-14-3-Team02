// 패키지명 정리(ENUM → enums)는 변환과 분리해 별도 PR로 진행한다.
@file:Suppress("ktlint:standard:package-name")

package com.prgms.backend.domain.content.ENUM

enum class ContentPreference {
    PREFER, // 3점
    AVAILABLE, // 2점
    DISLIKE, // 1점
    ;

    fun score(): Int =
        when (this) {
            PREFER -> 3
            AVAILABLE -> 2
            DISLIKE -> 1
        }
}
