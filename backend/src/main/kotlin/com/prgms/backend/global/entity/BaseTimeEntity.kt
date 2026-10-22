package com.prgms.backend.global.entity

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.LastModifiedDate
import java.time.LocalDateTime

@MappedSuperclass
abstract class BaseTimeEntity : BaseCreatedEntity() {
    @field:LastModifiedDate
    @field:Column(nullable = false)
    lateinit var updatedAt: LocalDateTime
        protected set
}
