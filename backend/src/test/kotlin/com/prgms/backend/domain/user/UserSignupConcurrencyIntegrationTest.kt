package com.prgms.backend.domain.user

import com.jayway.jsonpath.JsonPath
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.support.MySqlContainerSupport
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
internal class UserSignupConcurrencyIntegrationTest : MySqlContainerSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @MockitoSpyBean
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `같은 이메일로 동시 가입하면 한 요청은 201, 나머지는 공통 오류 형식의 409다`() {
        val suffix = UUID.randomUUID().toString()
        assertSingleRegistration(
            listOf(
                Registration("$suffix@test.com", "first-$suffix"),
                Registration("$suffix@test.com", "second-$suffix"),
            ),
        )
    }

    @Test
    fun `같은 닉네임으로 동시 가입하면 한 요청은 201, 나머지는 공통 오류 형식의 409다`() {
        val suffix = UUID.randomUUID().toString()
        assertSingleRegistration(
            listOf(
                Registration("first-$suffix@test.com", suffix),
                Registration("second-$suffix@test.com", suffix),
            ),
        )
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = [
            "fk_unknown_user",
            "UK2ty1xmrrgtn89xt7kyxx6ta7h_other",
            "other_table.UK6dotkott2kjsp8vw4d0m25fb7",
        ],
    )
    fun `이메일·닉네임 중복으로 식별되지 않는 DB 오류는 409로 변환하지 않는다`(constraintName: String?) {
        doThrow(
            DataIntegrityViolationException(
                "내부 DB 오류 상세",
                ConstraintViolationException("내부 제약 오류", SQLException("내부 SQL 오류"), constraintName),
            ),
        ).whenever(users).saveAndFlush(any<User>())
        val suffix = UUID.randomUUID().toString()

        mvc
            .perform(
                post("/api/user/sign-up")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signUpJson("$suffix@test.com", suffix)),
            ).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    private fun assertSingleRegistration(requests: List<Registration>) {
        val bothChecksPassed = CyclicBarrier(requests.size)
        doAnswer { invocation ->
            // Spring Data의 파생 쿼리는 인터페이스 메서드라 callRealMethod 대신 실제 SQL로 조회한다.
            val nickname: String = invocation.getArgument(0)
            val count: Int? =
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE nickname = ?",
                    Int::class.javaObjectType,
                    nickname,
                )
            val exists = checkNotNull(count) { "COUNT 조회 결과가 없습니다." } > 0
            assertFalse(exists, "두 요청 모두 INSERT 전에 실제 중복 검사를 통과해야 한다.")
            bothChecksPassed.await(10, TimeUnit.SECONDS)
            exists
        }.whenever(users).existsByNickname(any())

        val executor = Executors.newFixedThreadPool(requests.size)
        try {
            val futures =
                requests.map { request ->
                    executor.submit(
                        Callable<MvcResult> {
                            mvc
                                .perform(
                                    post("/api/user/sign-up")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(signUpJson(request.email, request.nickname)),
                                ).andReturn()
                        },
                    )
                }
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            val (first, second) = requests
            val count: Int? =
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE email IN (?, ?) OR nickname IN (?, ?)",
                    Int::class.javaObjectType,
                    first.email,
                    second.email,
                    first.nickname,
                    second.nickname,
                )
            assertEquals(1, count)
            assertEquals(listOf(201, 409), results.map { it.response.status }.sorted())
            for (result in results) {
                val body = result.response.contentAsString
                val status = result.response.status
                assertEquals(status, JsonPath.read<Number>(body, "$.code").toInt())
                assertEquals(status == 201, JsonPath.read<Boolean>(body, "$.success"))
                if (status == 409) {
                    assertEquals(
                        "이미 존재하는 이메일 또는 닉네임이 사용되었습니다.",
                        JsonPath.read<String>(body, "$.message"),
                    )
                    assertNull(JsonPath.read<Any?>(body, "$.data"))
                } else {
                    assertNotNull(JsonPath.read<Any?>(body, "$.data.userId"))
                }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun signUpJson(
        email: String,
        nickname: String,
    ): String = """{"email":"$email","nickname":"$nickname","password":"Passw0rd!","confirmPassword":"Passw0rd!"}"""

    private data class Registration(
        val email: String,
        val nickname: String,
    )
}
