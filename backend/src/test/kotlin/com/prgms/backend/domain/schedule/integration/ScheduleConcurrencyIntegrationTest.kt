package com.prgms.backend.domain.schedule.integration

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.notification.entity.Notification
import com.prgms.backend.domain.notification.repository.NotificationRepository
import com.prgms.backend.domain.schedule.candidate.dto.ScheduleCandidateRequest
import com.prgms.backend.domain.schedule.candidate.repository.ScheduleCandidateRepository
import com.prgms.backend.domain.schedule.candidate.service.ScheduleCandidateService
import com.prgms.backend.domain.schedule.poll.dto.SchedulePollRequest
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.schedule.poll.service.SchedulePollCloseService
import com.prgms.backend.domain.schedule.poll.service.SchedulePollService
import com.prgms.backend.domain.schedule.scheduler.SchedulePollCloseScheduler
import com.prgms.backend.domain.schedule.vote.dto.ScheduleVoteRequest
import com.prgms.backend.domain.schedule.vote.entity.SchedulePreference
import com.prgms.backend.domain.schedule.vote.repository.ScheduleVoteRepository
import com.prgms.backend.domain.schedule.vote.service.ScheduleVoteService
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.global.exception.custom.schedule.ScheduleCandidateHasVotesException
import com.prgms.backend.global.exception.custom.schedule.ScheduleCandidateLimitExceededException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@SpringBootTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "custom.jwt.access-secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "custom.jwt.refresh-secret=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class ScheduleConcurrencyIntegrationTest {
    companion object {
        @Container
        @JvmField
        val MYSQL =
            MySQLContainer("mysql:8.0")
                .withDatabaseName("schedule_test")

        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", { MYSQL.jdbcUrl })
            registry.add("spring.datasource.username", { MYSQL.username })
            registry.add("spring.datasource.password", { MYSQL.password })
        }
    }

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var meetingRepository: MeetingRepository

    @Autowired private lateinit var meetingMemberRepository: MeetingMemberRepository

    @MockitoSpyBean private lateinit var schedulePollRepository: SchedulePollRepository

    @MockitoBean private lateinit var closeScheduler: SchedulePollCloseScheduler

    @Autowired private lateinit var scheduleCandidateRepository: ScheduleCandidateRepository

    @Autowired private lateinit var scheduleVoteRepository: ScheduleVoteRepository

    @Autowired private lateinit var schedulePollService: SchedulePollService

    @Autowired private lateinit var closeService: SchedulePollCloseService

    @Autowired private lateinit var candidateService: ScheduleCandidateService

    @Autowired private lateinit var voteService: ScheduleVoteService

    @MockitoSpyBean private lateinit var notificationRepository: NotificationRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    private var meetingId: Long = 0
    private var hostId: Long = 0
    private var meetingMemberId: Long = 0

    @BeforeEach
    fun setUp() {
        assertEquals(
            "REPEATABLE-READ",
            jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String::class.java),
        )
        val suffix = UUID.randomUUID().toString()
        val host = userRepository.save(User("host-" + suffix + "@example.com", "host-" + suffix, "password"))
        val meeting = meetingRepository.save(Meeting(host, "일정 테스트", ""))
        val member = meetingMemberRepository.save(MeetingMember(meeting, host))

        meetingId = meeting.id
        hostId = host.id
        meetingMemberId = member.id
        schedulePollService.createPoll(
            meetingId,
            hostId,
            SchedulePollRequest.Create(LocalDateTime.now().plusDays(7)),
        )
    }

    @Test
    fun `9개일 때 두 후보 추가가 겹쳐도 10개를 넘지 않는다`() {
        val firstDate = LocalDate.now().plusDays(1)
        for (i in 0 until 9) {
            candidateService.createCandidate(
                meetingId,
                hostId,
                ScheduleCandidateRequest.Create(firstDate.plusDays(i.toLong())),
            )
        }

        val firstInserted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first =
                holdTransaction(
                    executor,
                    {
                        candidateService.createCandidate(
                            meetingId,
                            hostId,
                            ScheduleCandidateRequest.Create(firstDate.plusDays(9)),
                        )
                    },
                    firstInserted,
                    releaseFirst,
                )
            await(firstInserted)
            val secondStarted = CountDownLatch(1)
            val second =
                executor.submit(
                    Callable {
                        secondStarted.countDown()
                        candidateService.createCandidate(
                            meetingId,
                            hostId,
                            ScheduleCandidateRequest.Create(firstDate.plusDays(10)),
                        )
                    },
                )

            assertWaiting(secondStarted, second)
            releaseFirst.countDown()
            first.get(20, TimeUnit.SECONDS)
            val failure = assertThrows<ExecutionException> { second.get(20, TimeUnit.SECONDS) }
            assertInstanceOf(ScheduleCandidateLimitExceededException::class.java, failure.cause)
            assertEquals(
                10,
                checkNotNull(schedulePollRepository.findByMeetingIdWithCandidates(meetingId)).getCandidates().size,
            )
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["UPDATE", "DELETE"])
    fun `투표와 경합하는 후보 수정·삭제는 커밋된 투표를 확인하고 거절된다`(operation: String) {
        val original = LocalDate.now().plusDays(1)
        val candidateId =
            candidateService
                .createCandidate(
                    meetingId,
                    hostId,
                    ScheduleCandidateRequest.Create(original),
                ).id

        val voteInserted = CountDownLatch(1)
        val releaseVote = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val vote =
                holdTransaction(
                    executor,
                    {
                        voteService.submit(
                            meetingId,
                            candidateId,
                            hostId,
                            ScheduleVoteRequest.Submit(SchedulePreference.PREFER),
                        )
                    },
                    voteInserted,
                    releaseVote,
                )
            await(voteInserted)
            val changeStarted = CountDownLatch(1)
            val change =
                executor.submit(
                    Callable {
                        changeStarted.countDown()
                        if (operation.equals("UPDATE")) {
                            candidateService.update(
                                meetingId,
                                candidateId,
                                hostId,
                                ScheduleCandidateRequest.Update(original.plusDays(1)),
                            )
                        } else {
                            candidateService.delete(meetingId, candidateId, hostId)
                        }
                    },
                )

            assertWaiting(changeStarted, change)
            releaseVote.countDown()
            vote.get(20, TimeUnit.SECONDS)
            val failure = assertThrows<ExecutionException> { change.get(20, TimeUnit.SECONDS) }
            assertInstanceOf(ScheduleCandidateHasVotesException::class.java, failure.cause)
            assertEquals(
                original,
                checkNotNull(scheduleCandidateRepository.findByIdOrNull(candidateId))
                    .candidateDate,
            )
        } finally {
            releaseVote.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `같은 회원의 첫 투표 두 건은 하나의 행으로 저장된다`() {
        val candidateId =
            candidateService
                .createCandidate(
                    meetingId,
                    hostId,
                    ScheduleCandidateRequest.Create(LocalDate.now().plusDays(1)),
                ).id

        val firstInserted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first =
                holdTransaction(
                    executor,
                    {
                        voteService.submit(
                            meetingId,
                            candidateId,
                            hostId,
                            ScheduleVoteRequest.Submit(SchedulePreference.PREFER),
                        )
                    },
                    firstInserted,
                    releaseFirst,
                )
            await(firstInserted)
            val secondStarted = CountDownLatch(1)
            val second =
                executor.submit(
                    Callable {
                        secondStarted.countDown()
                        voteService.submit(
                            meetingId,
                            candidateId,
                            hostId,
                            ScheduleVoteRequest.Submit(SchedulePreference.AVAILABLE),
                        )
                    },
                )

            assertWaiting(secondStarted, second)
            releaseFirst.countDown()
            first.get(20, TimeUnit.SECONDS)
            second.get(20, TimeUnit.SECONDS)
            assertEquals(
                SchedulePreference.AVAILABLE,
                checkNotNull(
                    scheduleVoteRepository.findByScheduleCandidateIdAndMeetingMemberId(candidateId, meetingMemberId),
                ).preference,
            )
            val pollId = checkNotNull(schedulePollRepository.findByMeetingId(meetingId)).id
            assertEquals(1, scheduleVoteRepository.findAllByScheduleCandidateSchedulePollId(pollId).size)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `마감 처리의 첫 조회 이후 가입한 회원도 마감 알림을 받는다`() {
        val pollId = checkNotNull(schedulePollRepository.findByMeetingId(meetingId)).id
        val suffix = UUID.randomUUID().toString()
        val newUser =
            userRepository.save(
                User("new-" + suffix + "@example.com", "new-" + suffix, "password"),
            )

        val meetingIdRead = CountDownLatch(1)
        val continueClose = CountDownLatch(1)
        doAnswer {
            // 같은 트랜잭션의 연결에서 첫 일반 조회를 실행한 뒤 가입을 끼워 넣는다.
            val result: Long? =
                jdbcTemplate.queryForObject(
                    "SELECT meeting_id FROM schedule_polls WHERE id = ?",
                    Long::class.java,
                    pollId,
                )
            meetingIdRead.countDown()
            await(continueClose)
            result
        }.whenever(schedulePollRepository).findMeetingIdByPollId(pollId)

        val executor = Executors.newSingleThreadExecutor()
        try {
            val closing = executor.submit { closeService.closeOneIfExpired(pollId) }
            if (!meetingIdRead.await(5, TimeUnit.SECONDS)) {
                if (closing.isDone) {
                    closing.get(1, TimeUnit.SECONDS)
                }
                throw AssertionError("마감 처리의 첫 조회를 관찰하지 못했습니다.")
            }

            val meeting = checkNotNull(meetingRepository.findByIdOrNull(meetingId))
            meetingMemberRepository.save(MeetingMember(meeting, newUser))
            jdbcTemplate.update(
                "UPDATE schedule_polls SET deadline = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
                pollId,
            )

            continueClose.countDown()
            closing.get(20, TimeUnit.SECONDS)

            assertEquals(
                SchedulePollStatus.CLOSED,
                checkNotNull(schedulePollRepository.findByIdOrNull(pollId)).status,
            )
            assertEquals(1, notificationRepository.findByReceiverIdOrderByCreatedAtDesc(newUser.id).size)
        } finally {
            continueClose.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `알림 저장에 실패해도 일정 투표 마감은 커밋된다`() {
        val pollId = checkNotNull(schedulePollRepository.findByMeetingId(meetingId)).id
        jdbcTemplate.update(
            "UPDATE schedule_polls SET deadline = ? WHERE id = ?",
            Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
            pollId,
        )
        doThrow(IllegalStateException("notification insert failure"))
            .whenever(notificationRepository)
            .save(any<Notification>())

        assertDoesNotThrow { closeService.closeOneIfExpired(pollId) }

        assertEquals(
            SchedulePollStatus.CLOSED,
            checkNotNull(schedulePollRepository.findByIdOrNull(pollId)).status,
        )
        assertTrue(notificationRepository.findByReceiverIdOrderByCreatedAtDesc(hostId).isEmpty())
    }

    private fun holdTransaction(
        executor: ExecutorService,
        action: () -> Unit,
        actionCompleted: CountDownLatch,
        release: CountDownLatch,
    ): Future<*> =
        executor.submit {
            transactionTemplate.executeWithoutResult {
                action()
                actionCompleted.countDown()
                await(release)
            }
        }

    private fun await(latch: CountDownLatch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS), "동시 요청 준비 시간이 초과됐습니다.")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError(e)
        }
    }

    private fun assertWaiting(
        started: CountDownLatch,
        request: Future<*>,
    ) {
        assertTrue(started.await(20, TimeUnit.SECONDS))
        assertThrows<TimeoutException>("첫 번째 트랜잭션이 끝나기 전에 두 번째 요청이 완료되면 안 됩니다.") {
            request.get(150, TimeUnit.MILLISECONDS)
        }
    }
}
