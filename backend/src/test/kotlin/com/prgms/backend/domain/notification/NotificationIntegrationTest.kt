package com.prgms.backend.domain.notification

import com.prgms.backend.domain.content.service.ContentPollCloseScheduler
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.notification.ENUM.NotificationType
import com.prgms.backend.domain.notification.repository.NotificationRepository
import com.prgms.backend.domain.notification.service.NotificationService
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.security.JwtTokenProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID

// 알림 저장(엔티티 매핑/수신 대상)과 조회·읽음 API(인증, 응답 필드, 권한)를 실제 MySQL로 검증
@SpringBootTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "custom.jwt.access-secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "custom.jwt.refresh-secret=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class NotificationIntegrationTest {
    // 백그라운드 스케줄러가 테스트 데이터에 개입하지 못하게 막음
    @MockitoBean
    private lateinit var backgroundScheduler: ContentPollCloseScheduler

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var jwt: JwtTokenProvider

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var meetingRepository: MeetingRepository

    @Autowired
    private lateinit var meetingMemberRepository: MeetingMemberRepository

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Autowired
    private lateinit var notificationService: NotificationService

    private lateinit var host: User
    private lateinit var alice: User
    private lateinit var leaver: User
    private var meetingId: Long = 0

    @BeforeEach
    fun setUp() {
        host = saveUser("host")
        alice = saveUser("alice")
        leaver = saveUser("leaver")
        val meeting: Meeting = meetingRepository.save(Meeting(host, "알림 통합 테스트", ""))
        meetingId = meeting.id
        meetingMemberRepository.save(MeetingMember(meeting, host))
        meetingMemberRepository.save(MeetingMember(meeting, alice))
        val left = meetingMemberRepository.save(MeetingMember(meeting, leaver))
        left.leave()
        meetingMemberRepository.save(left)
    }

    @Test
    fun `마감 알림은 종류별 타입·제목·이동 경로로 저장되고, 참여 중인 모임원에게만 가며 처음엔 안 읽음`() {
        notificationService.notifyContentClosed(meetingId)
        notificationService.notifyScheduleClosed(meetingId)
        notificationService.notifySettlementClosed(meetingId)

        val mine = notificationRepository.findByReceiverIdOrderByCreatedAtDesc(alice.id)
        assertEquals(3, mine.size)
        assertEquals(1, mine.count { it.type == NotificationType.CONTENT_CLOSED })
        val content = mine.first { it.type == NotificationType.CONTENT_CLOSED }
        assertEquals("콘텐츠 투표가 마감되었습니다.", content.title)
        assertEquals("/meetings/$meetingId/content-poll", content.redirectUrl)
        assertEquals(meetingId, content.meetingId)
        assertFalse(content.isRead)
        assertTrue(mine.any { it.redirectUrl == "/meetings/$meetingId/schedule-poll" })
        assertEquals(3, notificationRepository.findByReceiverIdOrderByCreatedAtDesc(host.id).size)
        assertEquals(0, notificationRepository.findByReceiverIdOrderByCreatedAtDesc(leaver.id).size)
    }

    @Test
    fun `알림 API는 토큰이 없으면 401`() {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized())
        mvc.perform(patch("/api/notifications/1/read")).andExpect(status().isUnauthorized())
    }

    @Test
    fun `내 알림만 최신순으로 내려주고, 응답 필드 이름이 유지됨`() {
        notificationService.notifyContentClosed(meetingId)
        Thread.sleep(50)
        notificationService.notifyScheduleClosed(meetingId)

        mvc
            .perform(auth(get("/api/notifications"), alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].type").value("SCHEDULE_CLOSED"))
            .andExpect(jsonPath("$.data[1].type").value("CONTENT_CLOSED"))
            .andExpect(jsonPath("$.data[0].id").exists())
            .andExpect(jsonPath("$.data[0].meetingId").value(meetingId))
            .andExpect(jsonPath("$.data[0].title").exists())
            .andExpect(jsonPath("$.data[0].content").exists())
            .andExpect(jsonPath("$.data[0].redirectUrl").exists())
            .andExpect(jsonPath("$.data[0].isRead").value(false))
            .andExpect(jsonPath("$.data[0].createdAt").exists())
        mvc
            .perform(auth(get("/api/notifications"), leaver))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `본인 알림은 읽음 처리되어 DB에도 반영되고, 다시 요청해도 읽음 상태 유지`() {
        notificationService.notifyContentClosed(meetingId)
        val target = notificationRepository.findByReceiverIdOrderByCreatedAtDesc(alice.id)[0]

        mvc
            .perform(auth(patch("/api/notifications/${target.id}/read"), alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.isRead").value(true))
        mvc
            .perform(auth(patch("/api/notifications/${target.id}/read"), alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.isRead").value(true))

        assertTrue(notificationRepository.findById(target.id).orElseThrow().isRead)
    }

    @Test
    fun `남의 알림은 403으로 거부되고 읽음 상태가 바뀌지 않으며, 없는 알림은 404`() {
        notificationService.notifyContentClosed(meetingId)
        val hosts = notificationRepository.findByReceiverIdOrderByCreatedAtDesc(host.id)[0]

        mvc
            .perform(auth(patch("/api/notifications/${hosts.id}/read"), alice))
            .andExpect(status().isForbidden())
        assertFalse(notificationRepository.findById(hosts.id).orElseThrow().isRead)

        mvc
            .perform(auth(patch("/api/notifications/999999999/read"), alice))
            .andExpect(status().isNotFound())
    }

    private fun auth(
        request: MockHttpServletRequestBuilder,
        user: User,
    ): MockHttpServletRequestBuilder =
        request.header(
            HttpHeaders.AUTHORIZATION,
            "Bearer " + jwt.createAccessToken(user.id),
        )

    private fun saveUser(prefix: String): User {
        val suffix = UUID.randomUUID().toString()
        return userRepository.save(User("$prefix-$suffix@example.com", "$prefix-$suffix", "password"))
    }

    companion object {
        @Container
        @JvmField
        val MYSQL: MySQLContainer = MySQLContainer("mysql:8.0").withDatabaseName("notification_test")

        @DynamicPropertySource
        @JvmStatic
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { MYSQL.jdbcUrl }
            registry.add("spring.datasource.username") { MYSQL.username }
            registry.add("spring.datasource.password") { MYSQL.password }
        }
    }
}
