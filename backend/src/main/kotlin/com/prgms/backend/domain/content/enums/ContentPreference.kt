package com.prgms.backend.domain.content.enums

enum class ContentPreference {
    PREFER,
    AVAILABLE,
    DISLIKE,
    ;

    fun score(): Int =
        when (this) {
            PREFER -> 3
            AVAILABLE -> 2
            DISLIKE -> 1
        }
}
