package com.prgms.backend

import com.prgms.backend.support.MySqlContainerSupport
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
internal class BackendApplicationTests : MySqlContainerSupport() {
    @Test
    fun `애플리케이션 컨텍스트가 정상적으로 시작된다`() {}
}
