package com.prgms.backend.domain.content

import com.prgms.backend.domain.content.dto.request.ContentPollRequest
import com.prgms.backend.domain.content.entity.ContentCandidate
import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.integration.ContentNotificationWriter
import com.prgms.backend.domain.content.repository.ContentCandidateRepository
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.content.service.ContentPollCloseScheduler
import com.prgms.backend.domain.content.service.ContentPollCloseService
import com.prgms.backend.domain.content.service.ContentPollService
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.enums.MeetingStatus
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.global.exception.custom.content.ContentPollAlreadyConfirmedException
import com.prgms.backend.global.exception.custom.content.ContentPollClosedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "custom.jwt.access-secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "custom.jwt.refresh-secret=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class ContentPollCloseConcurrencyIntegrationTest {
    // 백그라운드 스케줄링이 테스트 데이터를 먼저 닫지 못하게 막음
    @MockitoBean
    private lateinit var closeScheduler: ContentPollCloseScheduler

    // 마감 이벤트로 알림 저장이 몇 번 호출되는지 확인
    @MockitoSpyBean
    private lateinit var notificationWriter: ContentNotificationWriter

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var meetingRepository: MeetingRepository

    @Autowired
    private lateinit var contentPollRepository: ContentPollRepository

    @Autowired
    private lateinit var contentCandidateRepository: ContentCandidateRepository

    @Autowired
    private lateinit var closeService: ContentPollCloseService

    @Autowired
    private lateinit var contentPollService: ContentPollService

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private var hostId: Long = 0
    private var meetingId: Long = 0
    private var pollId: Long = 0

    @BeforeEach
    fun setUp() {
        val suffix = UUID.randomUUID().toString()
        val host = userRepository.save(User("host-$suffix@example.com", "host-$suffix", "password"))
        hostId = host.id
        meetingId = meetingRepository.save(Meeting(host, "콘텐츠 동시성 테스트", "")).id
        pollId = contentPollRepository.save(ContentPoll(meetingId, LocalDateTime.now().minusMinutes(1))).id
    }

    @Test
    fun `만료 투표판 조회는 ACTIVE 모임의 마감기한 지난 OPEN 투표판만 대상`() {
        val otherHost =
            userRepository.save(
                User("other-${UUID.randomUUID()}@example.com", "other-${UUID.randomUUID()}", "password"),
            )

        val activeNotYet = meetingRepository.save(Meeting(otherHost, "마감 전", ""))
        val notYetId =
            contentPollRepository
                .save(ContentPoll(activeNotYet.id, LocalDateTime.now().plusDays(1)))
                .id

        val completed = meetingRepository.save(Meeting(otherHost, "종료", ""))
        val completedId =
            contentPollRepository
                .save(ContentPoll(completed.id, LocalDateTime.now().minusMinutes(1)))
                .id
        completed.complete()
        meetingRepository.save(completed)

        val deleted = meetingRepository.save(Meeting(otherHost, "삭제", ""))
        val deletedId =
            contentPollRepository
                .save(ContentPoll(deleted.id, LocalDateTime.now().minusMinutes(1)))
                .id
        deleted.softDelete()
        meetingRepository.save(deleted)

        val ids =
            contentPollRepository.findExpiredIds(
                ContentPollStatus.OPEN,
                MeetingStatus.ACTIVE,
                LocalDateTime.now(),
            )

        assertTrue(ids.contains(pollId))
        assertFalse(ids.contains(notYetId))
        assertFalse(ids.contains(completedId))
        assertFalse(ids.contains(deletedId))
    }

    @Test
    fun `같은 투표판을 동시에 마감해도 한 번만 닫고 알림도 한 번만 저장`() {
        val firstClosed = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            // 첫 번째 마감: 투표판 락을 잡은 채 커밋 직전에 대기
            val first =
                holdTransaction(
                    executor,
                    { closeService.closeOneIfExpired(pollId) },
                    firstClosed,
                    releaseFirst,
                )
            await(firstClosed)

            val secondStarted = CountDownLatch(1)
            val second =
                executor.submit(
                    Runnable {
                        secondStarted.countDown()
                        closeService.closeOneIfExpired(pollId)
                    },
                )

            assertWaiting(secondStarted, second)
            releaseFirst.countDown()
            first.get(20, TimeUnit.SECONDS)
            second.get(20, TimeUnit.SECONDS)

            assertEquals(
                ContentPollStatus.CLOSED,
                contentPollRepository.findById(pollId).orElseThrow().status,
            )
            verify(notificationWriter, times(1)).save(meetingId)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `투표판 락을 잡은 쪽이 마감기한을 연장하면 기다린 마감은 재검증 후 건너뜀`() {
        val deadlineChanged = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val update =
                holdTransaction(
                    executor,
                    {
                        val poll = checkNotNull(contentPollRepository.findByIdForUpdate(pollId))
                        poll.changeDeadLine(LocalDateTime.now().plusDays(1))
                    },
                    deadlineChanged,
                    releaseUpdate,
                )
            await(deadlineChanged)

            val closeStarted = CountDownLatch(1)
            val close =
                executor.submit(
                    Runnable {
                        closeStarted.countDown()
                        closeService.closeOneIfExpired(pollId)
                    },
                )

            assertWaiting(closeStarted, close)
            releaseUpdate.countDown()
            update.get(20, TimeUnit.SECONDS)
            close.get(20, TimeUnit.SECONDS)

            assertEquals(
                ContentPollStatus.OPEN,
                contentPollRepository.findById(pollId).orElseThrow().status,
            )
            verify(notificationWriter, times(0)).save(meetingId)
        } finally {
            releaseUpdate.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `모임 락을 잡은 쪽이 모임을 종료하면 기다린 마감은 재검증 후 건너뜀`() {
        val meetingCompleted = CountDownLatch(1)
        val releaseComplete = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val complete =
                holdTransaction(
                    executor,
                    {
                        val meeting = meetingRepository.findByIdAndDeletedAtIsNullForUpdate(meetingId)
                        assertNotNull(meeting)
                        checkNotNull(meeting).complete()
                    },
                    meetingCompleted,
                    releaseComplete,
                )
            await(meetingCompleted)

            val closeStarted = CountDownLatch(1)
            val close =
                executor.submit(
                    Runnable {
                        closeStarted.countDown()
                        closeService.closeOneIfExpired(pollId)
                    },
                )

            assertWaiting(closeStarted, close)
            releaseComplete.countDown()
            complete.get(20, TimeUnit.SECONDS)
            close.get(20, TimeUnit.SECONDS)

            assertEquals(
                ContentPollStatus.OPEN,
                contentPollRepository.findById(pollId).orElseThrow().status,
            )
            verify(notificationWriter, times(0)).save(meetingId)
        } finally {
            releaseComplete.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `마감이 투표판 락을 쥐고 있으면 마감기한 수정은 기다렸다가 마감된 투표판이라 거부`() {
        val closed = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val close =
                holdTransaction(
                    executor,
                    { closeService.closeOneIfExpired(pollId) },
                    closed,
                    releaseClose,
                )
            await(closed)

            val updateStarted = CountDownLatch(1)
            val update =
                executor.submit(
                    Callable {
                        updateStarted.countDown()
                        contentPollService.updateDeadline(
                            meetingId,
                            hostId,
                            ContentPollRequest.UpdateDeadline(LocalDateTime.now().plusDays(1)),
                        )
                    },
                )

            assertWaiting(updateStarted, update)
            releaseClose.countDown()
            close.get(20, TimeUnit.SECONDS)
            val failure = assertThrows<ExecutionException> { update.get(20, TimeUnit.SECONDS) }
            assertInstanceOf(ContentPollClosedException::class.java, failure.cause)
            assertEquals(
                ContentPollStatus.CLOSED,
                contentPollRepository.findById(pollId).orElseThrow().status,
            )
        } finally {
            releaseClose.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `같은 후보 확정 요청이 겹치면 두 번째는 기다렸다가 이미 확정됨으로 거부`() {
        closeService.closeOneIfExpired(pollId)
        val poll = contentPollRepository.findById(pollId).orElseThrow()
        val firstCandidateId = contentCandidateRepository.save(ContentCandidate(poll, 1L, "후보1", "")).id
        val secondCandidateId = contentCandidateRepository.save(ContentCandidate(poll, 1L, "후보2", "")).id

        val firstConfirmed = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first =
                holdTransaction(
                    executor,
                    {
                        contentPollService.confirm(
                            meetingId,
                            hostId,
                            ContentPollRequest.Confirm(firstCandidateId),
                        )
                    },
                    firstConfirmed,
                    releaseFirst,
                )
            await(firstConfirmed)

            val secondStarted = CountDownLatch(1)
            val second =
                executor.submit(
                    Callable {
                        secondStarted.countDown()
                        contentPollService.confirm(
                            meetingId,
                            hostId,
                            ContentPollRequest.Confirm(secondCandidateId),
                        )
                    },
                )

            assertWaiting(secondStarted, second)
            releaseFirst.countDown()
            first.get(20, TimeUnit.SECONDS)
            val failure = assertThrows<ExecutionException> { second.get(20, TimeUnit.SECONDS) }
            assertInstanceOf(ContentPollAlreadyConfirmedException::class.java, failure.cause)
            assertEquals(
                firstCandidateId,
                contentPollRepository.findById(pollId).orElseThrow().confirmedCandidateId,
            )
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    private fun holdTransaction(
        executor: ExecutorService,
        action: () -> Unit,
        actionCompleted: CountDownLatch,
        release: CountDownLatch,
    ): Future<*> =
        executor.submit(
            Runnable {
                transactionTemplate.executeWithoutResult {
                    action()
                    actionCompleted.countDown()
                    await(release)
                }
            },
        )

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
        Thread.sleep(150)
        assertFalse(request.isDone, "첫 번째 트랜잭션이 끝나기 전에 두 번째 요청이 완료됐습니다.")
    }

    companion object {
        @Container
        @JvmField
        val MYSQL: MySQLContainer = MySQLContainer("mysql:8.0").withDatabaseName("content_concurrency_test")

        @DynamicPropertySource
        @JvmStatic
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { MYSQL.jdbcUrl }
            registry.add("spring.datasource.username") { MYSQL.username }
            registry.add("spring.datasource.password") { MYSQL.password }
        }
    }
}
