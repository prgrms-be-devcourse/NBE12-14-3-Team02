package com.prgms.backend.domain.meeting

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingInvitation
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingInvitationRepository
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.meeting.service.MeetingInvitationService
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.global.exception.custom.meeting.AlreadyMeetingMemberException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull

@DataJpaTest(
    showSql = false,
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "logging.level.org.hibernate.SQL=OFF",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MeetingInvitationService::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
internal class MeetingInvitationConcurrencyIntegrationTest {
    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var meetings: MeetingRepository

    @Autowired
    private lateinit var invitations: MeetingInvitationRepository

    @MockitoSpyBean
    private lateinit var members: MeetingMemberRepository

    @Autowired
    private lateinit var service: MeetingInvitationService

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactions: TransactionTemplate

    private lateinit var meeting: Meeting
    private lateinit var user: User
    private lateinit var inviteCode: String

    @BeforeEach
    fun setUp() {
        val host = createUser()
        user = createUser()
        meeting = meetings.saveAndFlush(Meeting(host, "초대 가입 동시성 테스트", ""))
        inviteCode = createInvitation()
    }

    @Test
    fun `같은 사용자와 같은 초대 코드로 동시에 가입하면 한 번 성공하고 나머지는 409`() {
        val request = JoinRequest(inviteCode, user.id)
        val results = joinConcurrently(listOf(request, request, request, request))

        assertResponses(results, 1, 3)
        assertOneMembership(user.id)
    }

    @Test
    fun `같은 모임의 서로 다른 초대 코드로 동시 가입해도 모임원은 한 행만 유지`() {
        val otherCode = createInvitation()
        val results =
            joinConcurrently(
                listOf(
                    JoinRequest(inviteCode, user.id),
                    JoinRequest(otherCode, user.id),
                ),
            )

        assertResponses(results, 1, 1)
        assertOneMembership(user.id)
    }

    @Test
    fun `서로 다른 사용자가 같은 초대 코드로 동시 가입하면 모두 정상 가입`() {
        val otherUser = createUser()
        val results =
            joinConcurrently(
                listOf(
                    JoinRequest(inviteCode, user.id),
                    JoinRequest(inviteCode, otherUser.id),
                ),
            )

        assertResponses(results, 2, 0)
        assertOneMembership(user.id)
        assertOneMembership(otherUser.id)
    }

    @Test
    fun `LEFT 모임원이 동시에 재가입하면 기존 ID를 재사용하고 한 요청만 성공`() {
        val member = MeetingMember(meeting, user)
        member.leave()
        val originalId = members.saveAndFlush(member).id
        val request = JoinRequest(inviteCode, user.id)

        val results = joinConcurrently(listOf(request, request))

        assertResponses(results, 1, 1)
        val rejoined: MeetingMember = members.findById(originalId).orElseThrow()
        assertEquals(MeetingMemberStatus.JOINED, rejoined.status)
        assertEquals(originalId, results.first { it.code == 200 }.memberId)
        assertOneMembership(user.id)
    }

    @Test
    fun `실제 MySQL 모임원 UNIQUE 위반도 409 예외로 변환하고 기존 행을 유지`() {
        val originalId = members.saveAndFlush(MeetingMember(meeting, user)).id
        // 사전 조회로 걸러지지 않은 상황을 만들어 실제 INSERT 제약 위반 경로를 검증한다.
        doReturn(null).whenever(members).findByMeetingIdAndUserIdForUpdate(meeting.id, user.id)

        val exception =
            assertThrows<AlreadyMeetingMemberException> {
                service.joinMeeting(inviteCode, user.id)
            }

        assertEquals(409, exception.code)
        val existingMember = members.findByMeetingIdAndUserId(meeting.id, user.id)
        assertNotNull(existingMember)
        assertEquals(originalId, existingMember.id)
        assertOneMembership(user.id)
    }

    private fun joinConcurrently(requests: List<JoinRequest>): List<JoinResult> {
        val snapshotsCreated = CyclicBarrier(requests.size)
        val executor = Executors.newFixedThreadPool(requests.size)
        try {
            val futures =
                requests.map { request ->
                    executor.submit<JoinResult> {
                        try {
                            val response =
                                checkNotNull(
                                    transactions.execute {
                                        // 각 트랜잭션에서 실제 조회로 스냅샷을 만든 뒤 잠금 경쟁을 시작한다.
                                        assertNotNull(invitations.findMeetingIdByInviteCode(request.code))
                                        assertDoesNotThrow { snapshotsCreated.await(10, TimeUnit.SECONDS) }
                                        service.joinMeeting(request.code, request.userId)
                                    },
                                ) { "가입 트랜잭션은 모임원 응답을 반환해야 합니다." }
                            JoinResult(200, response.id)
                        } catch (e: AlreadyMeetingMemberException) {
                            JoinResult(e.code, null)
                        }
                    }
                }
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun assertResponses(
        results: List<JoinResult>,
        successes: Int,
        conflicts: Int,
    ) {
        assertEquals(successes, results.count { it.code == 200 })
        assertEquals(conflicts, results.count { it.code == 409 })
    }

    private fun assertOneMembership(userId: Long) {
        val count: Long? =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM meeting_members WHERE meeting_id = ? AND user_id = ?",
                Long::class.javaObjectType,
                meeting.id,
                userId,
            )
        assertEquals(1L, count)
    }

    private fun createUser(): User {
        val suffix: String = UUID.randomUUID().toString()
        return users.saveAndFlush(User("$suffix@test.com", suffix, "password"))
    }

    private fun createInvitation(): String {
        val code: String = UUID.randomUUID().toString()
        invitations.saveAndFlush(MeetingInvitation(meeting, code, LocalDateTime.now().plusDays(1)))
        return code
    }

    private data class JoinRequest(
        val code: String,
        val userId: Long,
    )

    private data class JoinResult(
        val code: Int,
        val memberId: Long?,
    )

    companion object {
        @Container
        @JvmField
        val mysql: MySQLContainer =
            MySQLContainer("mysql:8.0").withDatabaseName("invitation_join_test")

        @DynamicPropertySource
        @JvmStatic
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
        }
    }
}
