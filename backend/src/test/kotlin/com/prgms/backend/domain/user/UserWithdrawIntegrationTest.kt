package com.prgms.backend.domain.user

import com.prgms.backend.domain.expense.entity.Expense
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.meeting.service.MeetingMemberService
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.service.UserWithdrawService
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberHasUnsettledExpenseException
import com.prgms.backend.support.MySqlContainerSupport
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.util.UUID

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserWithdrawService::class, MeetingMemberService::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
internal class UserWithdrawIntegrationTest : MySqlContainerSupport() {
    @Autowired
    private lateinit var service: UserWithdrawService

    @MockitoSpyBean
    private lateinit var memberService: MeetingMemberService

    @MockitoSpyBean
    private lateinit var members: MeetingMemberRepository

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var meetings: MeetingRepository

    @Autowired
    private lateinit var expenses: ExpenseRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private var userId: Long = 0
    private var firstMeetingId: Long = 0
    private var secondMeetingId: Long = 0
    private var secondMemberId: Long = 0

    @BeforeEach
    fun setUp() {
        transactions.executeWithoutResult {
            val suffix = UUID.randomUUID().toString()
            val host = users.save(User("host-$suffix@test.com", "host-$suffix", "encoded"))
            val user = users.save(User("user-$suffix@test.com", "user-$suffix", "encoded"))
            user.updateRefreshToken("refresh-token")
            userId = user.id
            val first = meetings.save(Meeting(host, "첫 모임", ""))
            val second = meetings.save(Meeting(host, "두 번째 모임", ""))
            firstMeetingId = first.id
            secondMeetingId = second.id
            members.save(MeetingMember(first, user))
            secondMemberId = members.save(MeetingMember(second, user)).id
        }

        // DB의 반환 순서에 의존하지 않고 실제 조회 결과의 처리 순서만 고정한다.
        doAnswer { _ ->
            members
                .findAllByUserIdAndStatus(userId, MeetingMemberStatus.JOINED)
                .filter { it.meeting.deletedAt == null }
                .sortedBy { it.meeting.id }
        }.whenever(members).findAllByUserIdAndStatusAndMeetingDeletedAtIsNull(userId, MeetingMemberStatus.JOINED)
    }

    @Test
    fun `첫 모임 탈퇴를 DB에 반영한 뒤 두 번째 모임에서 실패하면 전체 변경을 롤백한다`() {
        expenses.saveAndFlush(
            Expense.register(
                secondMeetingId,
                secondMemberId,
                "미정산 지출",
                100,
                null,
                mapOf(secondMemberId to 100L),
                "EXACT",
                null,
                null,
            ),
        )

        doAnswer { invocation ->
            // 첫 모임의 변경이 실제 SQL로 실행됐음을 확인한 뒤 실제 업무 예외를 발생시킨다.
            entityManager.flush()
            assertEquals("LEFT", membershipStatus(firstMeetingId))
            assertNotNull(
                jdbc.queryForObject(
                    "SELECT left_at FROM meeting_members WHERE meeting_id = ? AND user_id = ?",
                    Timestamp::class.java,
                    firstMeetingId,
                    userId,
                ),
            )
            invocation.callRealMethod()
        }.whenever(memberService).leaveMeeting(secondMeetingId, userId)

        assertThrows<MeetingMemberHasUnsettledExpenseException> { service.withdraw(userId) }

        // 서비스 트랜잭션이 종료된 후 DB를 직접 조회해 1차 캐시의 영향을 배제한다.
        assertEquals("JOINED", membershipStatus(firstMeetingId))
        assertEquals("JOINED", membershipStatus(secondMeetingId))
        assertEquals(0, leftMembershipCount())
        assertEquals("ACTIVE", userColumn("status", String::class.java))
        assertNull(userColumn("deleted_at", Timestamp::class.java))
        assertEquals("refresh-token", userColumn("refresh_token", String::class.java))
    }

    @Test
    fun `탈퇴에 성공하면 모든 모임 탈퇴와 회원 상태 변경 및 토큰 삭제를 함께 커밋한다`() {
        service.withdraw(userId)

        assertEquals("LEFT", membershipStatus(firstMeetingId))
        assertEquals("LEFT", membershipStatus(secondMeetingId))
        assertEquals(2, leftMembershipCount())
        assertEquals("WITHDRAWN", userColumn("status", String::class.java))
        assertNotNull(userColumn("deleted_at", Timestamp::class.java))
        assertNull(userColumn("refresh_token", String::class.java))
    }

    private fun membershipStatus(meetingId: Long): String? =
        jdbc.queryForObject(
            "SELECT status FROM meeting_members WHERE meeting_id = ? AND user_id = ?",
            String::class.java,
            meetingId,
            userId,
        )

    private fun leftMembershipCount(): Int? =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM meeting_members WHERE user_id = ? AND left_at IS NOT NULL",
            Int::class.javaObjectType,
            userId,
        )

    private fun <T : Any> userColumn(
        column: String,
        type: Class<T>,
    ): T? = jdbc.queryForObject("SELECT $column FROM users WHERE id = ?", type, userId)
}
