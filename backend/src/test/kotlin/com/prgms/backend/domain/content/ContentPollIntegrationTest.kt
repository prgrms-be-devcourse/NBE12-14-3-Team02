package com.prgms.backend.domain.content

import com.prgms.backend.domain.content.dto.request.ContentVoteRequest
import com.prgms.backend.domain.content.entity.ContentCandidate
import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.entity.ContentVote
import com.prgms.backend.domain.content.enums.ContentPollResultSort
import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.enums.ContentPreference
import com.prgms.backend.domain.content.integration.ContentNotificationWriter
import com.prgms.backend.domain.content.repository.ContentCandidateRepository
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.content.repository.ContentVoteRepository
import com.prgms.backend.domain.content.service.ContentCandidateService
import com.prgms.backend.domain.content.service.ContentPollCloseScheduler
import com.prgms.backend.domain.content.service.ContentPollCloseService
import com.prgms.backend.domain.content.service.ContentPollService
import com.prgms.backend.domain.content.service.ContentVoteService
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.meeting.service.MeetingService
import com.prgms.backend.domain.notification.ENUM.NotificationType
import com.prgms.backend.domain.notification.repository.NotificationRepository
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.global.exception.custom.meeting.MeetingPollNotCompletedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

// 콘텐츠 투표(투표판/후보/투표/마감/확정/결과) 전체 흐름을 실제 MySQL로 검증하는 통합테스트
@SpringBootTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "custom.jwt.access-secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "custom.jwt.refresh-secret=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class ContentPollIntegrationTest {
    // 백그라운드 스케줄링이 테스트 데이터를 먼저 닫지 못하게 막고, 스케줄러는 직접 만들어 호출
    @MockitoBean
    private lateinit var backgroundScheduler: ContentPollCloseScheduler

    @MockitoSpyBean
    private lateinit var notificationWriter: ContentNotificationWriter

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var meetingRepository: MeetingRepository

    @Autowired
    private lateinit var meetingMemberRepository: MeetingMemberRepository

    @Autowired
    private lateinit var pollRepository: ContentPollRepository

    @Autowired
    private lateinit var candidateRepository: ContentCandidateRepository

    @Autowired
    private lateinit var voteRepository: ContentVoteRepository

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Autowired
    private lateinit var pollService: ContentPollService

    @Autowired
    private lateinit var candidateService: ContentCandidateService

    @Autowired
    private lateinit var voteService: ContentVoteService

    @Autowired
    private lateinit var closeService: ContentPollCloseService

    @Autowired
    private lateinit var meetingService: MeetingService

    private lateinit var host: User
    private lateinit var alice: User
    private lateinit var bob: User
    private lateinit var outsider: User
    private lateinit var meeting: Meeting
    private var meetingId: Long = 0
    private lateinit var hostMember: MeetingMember
    private lateinit var aliceMember: MeetingMember
    private lateinit var bobMember: MeetingMember

    @BeforeEach
    fun setUp() {
        host = saveUser("host")
        alice = saveUser("alice")
        bob = saveUser("bob")
        outsider = saveUser("outsider")
        meeting = meetingRepository.save(Meeting(host, "콘텐츠 통합 테스트", ""))
        meetingId = meeting.id
        hostMember = meetingMemberRepository.save(MeetingMember(meeting, host))
        aliceMember = meetingMemberRepository.save(MeetingMember(meeting, alice))
        bobMember = meetingMemberRepository.save(MeetingMember(meeting, bob))
    }

    // ---------- 투표판 생성 / 조회 ----------

    // ---------- 후보 ----------

    // ---------- 투표 ----------

    @Test
    fun `같은 사람이 같은 후보에 동시에 투표해도 표는 한 개만 남음`() {
        openPoll()
        val a = candidate("A", aliceMember)
        val threads = 5
        val executor = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        try {
            val futures = mutableListOf<Future<Boolean>>()
            for (i in 0 until threads) {
                val preference = if (i % 2 == 0) ContentPreference.PREFER else ContentPreference.DISLIKE
                futures.add(
                    executor.submit(
                        Callable {
                            ready.countDown()
                            go.await(10, TimeUnit.SECONDS)
                            try {
                                voteService.upsert(meetingId, bob.id, ContentVoteRequest.Submit(a.id, preference))
                                true
                            } catch (e: RuntimeException) {
                                // 동시에 첫 표를 넣다 UNIQUE에 걸린 쪽은 실패할 수 있음
                                false
                            }
                        },
                    ),
                )
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            var succeeded = 0L
            for (future in futures) {
                if (future.get(30, TimeUnit.SECONDS)) succeeded++
            }

            assertTrue(succeeded >= 1)
            assertEquals(1, voteRepository.findByContentCandidate_ContentPoll_Id(a.contentPoll.id).size)
        } finally {
            go.countDown()
            executor.shutdownNow()
        }
    }

    // ---------- 마감기한 수정 ----------

    // ---------- 스케줄러 마감 + 알림 ----------

    @Test
    fun `스케줄러가 마감기한 지난 투표판만 닫고 모든 모임원에게 알림을 한 번씩 저장`() {
        val expired = pollRepository.save(ContentPoll(meetingId, LocalDateTime.now().minusMinutes(1)))
        val otherMeeting = meetingRepository.save(Meeting(host, "마감 전 모임", ""))
        val notYet = pollRepository.save(ContentPoll(otherMeeting.id, LocalDateTime.now().plusDays(1)))
        val before = contentClosedNotifications(alice.id)

        scheduler().closeExpired()

        assertEquals(ContentPollStatus.CLOSED, pollRepository.findById(expired.id).orElseThrow().status)
        assertEquals(ContentPollStatus.OPEN, pollRepository.findById(notYet.id).orElseThrow().status)
        assertEquals(before + 1, contentClosedNotifications(alice.id))
        assertEquals(1, contentClosedNotifications(host.id))
        assertEquals(1, contentClosedNotifications(bob.id))

        // 이미 닫힌 투표판은 다시 닫히거나 알림이 중복 저장되지 않음
        scheduler().closeExpired()
        assertEquals(before + 1, contentClosedNotifications(alice.id))
    }

    @Test
    fun `알림 저장이 실패해도 투표판 마감은 되돌아가지 않음`() {
        val expired = pollRepository.save(ContentPoll(meetingId, LocalDateTime.now().minusMinutes(1)))
        doThrow(IllegalStateException("알림 저장 실패")).whenever(notificationWriter).save(anyLong())

        closeService.closeOneIfExpired(expired.id)

        assertEquals(ContentPollStatus.CLOSED, pollRepository.findById(expired.id).orElseThrow().status)
        assertEquals(0, contentClosedNotifications(alice.id))
    }

    // ---------- 확정 ----------

    // ---------- 모임 종료와의 관계 ----------

    @Test
    fun `열린 콘텐츠 투표판이 있으면 모임을 종료할 수 없고, 마감 뒤에는 종료할 수 있음`() {
        val poll = openPoll()

        assertThrows<MeetingPollNotCompletedException> {
            meetingService.completeMeeting(meetingId, host.id)
        }

        close(poll)
        meetingService.completeMeeting(meetingId, host.id)
        assertFalse(meetingRepository.findById(meetingId).orElseThrow().isActive())
    }

    // ---------- 결과 ----------

    @Test
    fun `마감 후 탈퇴한 사람의 표도 참여 인원·미응답 수·인원별 결과에 포함되어 합계가 맞음`() {
        val poll = openPoll()
        val a = candidate("A", hostMember)
        val b = candidate("B", bobMember)
        vote(a, hostMember, ContentPreference.PREFER)
        vote(a, aliceMember, ContentPreference.AVAILABLE)
        vote(a, bobMember, ContentPreference.DISLIKE)
        vote(b, hostMember, ContentPreference.AVAILABLE)
        vote(b, aliceMember, ContentPreference.PREFER)
        vote(b, bobMember, ContentPreference.PREFER)
        close(poll)
        leave(bobMember)

        val results = pollService.getResults(meetingId, host.id, ContentPollResultSort.SCORE)

        assertEquals(3, results.joinedCount)
        assertEquals(3, results.members.size)
        for (candidate in results.candidates) {
            assertEquals(3, candidate.responseCount)
            assertEquals(0, candidate.noResponseCount)
        }
        assertTrue(results.members.any { it.meetingMemberId == bobMember.id })
        // 후보 등록자가 탈퇴해도 닉네임은 유지
        val byBob = results.candidates.first { it.candidateId == b.id }
        assertTrue(checkNotNull(byBob.createdByNickname).startsWith("bob"))
    }

    @Test
    fun `표 없이 탈퇴한 사람은 미응답으로 잡히지 않고, 미응답 수는 음수가 되지 않음`() {
        val poll = openPoll()
        val a = candidate("A", hostMember)
        vote(a, bobMember, ContentPreference.PREFER)
        close(poll)
        leave(bobMember)
        leave(aliceMember)

        val results = pollService.getResults(meetingId, host.id, ContentPollResultSort.SCORE)

        // 호스트(미응답) + 표를 낸 탈퇴자 = 2명, 표 없이 나간 alice는 제외
        assertEquals(2, results.joinedCount)
        val result = results.candidates[0]
        assertEquals(1, result.responseCount)
        assertEquals(1, result.noResponseCount)
        assertTrue(results.members.none { it.meetingMemberId == aliceMember.id })
    }

    // ---------- helpers ----------

    private fun scheduler(): ContentPollCloseScheduler = ContentPollCloseScheduler(pollRepository, closeService)

    private fun contentClosedNotifications(receiverId: Long): Long =
        notificationRepository
            .findByReceiverIdOrderByCreatedAtDesc(receiverId)
            .count { it.meetingId == meetingId && it.type == NotificationType.CONTENT_CLOSED }
            .toLong()

    private fun openPoll(): ContentPoll = pollRepository.save(ContentPoll(meetingId, LocalDateTime.now().plusDays(1)))

    private fun close(poll: ContentPoll) {
        val stored = pollRepository.findById(poll.id).orElseThrow()
        stored.close()
        pollRepository.save(stored)
    }

    private fun candidate(
        title: String,
        creator: MeetingMember,
    ): ContentCandidate {
        val poll = checkNotNull(pollRepository.findByMeetingId(meetingId))
        return candidateRepository.save(ContentCandidate(poll, creator.id, title, ""))
    }

    private fun vote(
        candidate: ContentCandidate,
        member: MeetingMember,
        preference: ContentPreference,
    ) {
        voteRepository.save(ContentVote(candidate, member.id, preference))
    }

    private fun leave(member: MeetingMember) {
        val stored = meetingMemberRepository.findById(member.id).orElseThrow()
        stored.leave()
        meetingMemberRepository.save(stored)
    }

    private fun saveUser(prefix: String): User {
        val suffix = UUID.randomUUID().toString()
        return userRepository.save(User("$prefix-$suffix@example.com", "$prefix-$suffix", "password"))
    }

    companion object {
        @Container
        @JvmField
        val MYSQL: MySQLContainer = MySQLContainer("mysql:8.0").withDatabaseName("content_flow_test")

        @DynamicPropertySource
        @JvmStatic
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { MYSQL.jdbcUrl }
            registry.add("spring.datasource.username") { MYSQL.username }
            registry.add("spring.datasource.password") { MYSQL.password }
        }
    }
}
