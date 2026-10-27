package com.prgms.backend.support

import com.prgms.backend.global.entity.BaseCreatedEntity
import com.prgms.backend.global.entity.BaseTimeEntity
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

// 단위 테스트에서는 JPA Auditing이 동작하지 않아 lateinit인 createdAt·updatedAt이 비어 있다.
// 응답 DTO가 읽기 전에 저장된 엔티티처럼 값을 채워준다.
object AuditFields {
    fun <T : BaseCreatedEntity> filled(entity: T): T {
        val now = LocalDateTime.now()
        ReflectionTestUtils.setField(entity, "createdAt", now)
        if (entity is BaseTimeEntity) {
            ReflectionTestUtils.setField(entity, "updatedAt", now)
        }
        return entity
    }
}
