package com.prgms.backend.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer

// 여러 테스트 클래스가 컨테이너 하나를 같이 쓰도록 static으로 한 번만 띄운다.
// ddl-auto=validate(application.yml)와 Flyway를 그대로 써서 엔티티와 실제 스키마가 맞는지도 함께 검증한다.
@Testcontainers(disabledWithoutDocker = true)
abstract class MySqlContainerSupport {
    companion object {
        private val mysql =
            MySQLContainer("mysql:8.0")
                .withDatabaseName("backend_test")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("custom.jwt.access-secret") {
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
            registry.add("custom.jwt.refresh-secret") {
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
            }
        }
    }
}
