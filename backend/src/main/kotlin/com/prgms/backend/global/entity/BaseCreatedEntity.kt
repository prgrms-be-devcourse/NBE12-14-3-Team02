package com.prgms.backend.global.entity

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseCreatedEntity {
    @field:CreatedDate
    @field:Column(nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set
}
