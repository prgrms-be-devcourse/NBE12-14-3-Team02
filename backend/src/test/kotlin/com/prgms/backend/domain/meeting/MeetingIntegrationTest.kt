package com.prgms.backend.domain.meeting

import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.expense.entity.Expense
import com.prgms.backend.domain.expense.repository.ExpenseRepository
import com.prgms.backend.domain.meeting.dto.request.MeetingCreateRequest
import com.prgms.backend.domain.meeting.dto.request.MeetingUpdateRequest
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingInvitation
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.enums.MeetingStatus
import com.prgms.backend.domain.meeting.repository.MeetingInvitationRepository
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.settlement.entity.Settlement
import com.prgms.backend.domain.settlement.repository.SettlementRepository
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.security.JwtTokenProvider
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceUnitUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import kotlin.test.assertNotNull

// JWT 인증부터 Controller, Service, Repository, MySQL까지 함께 검증한다.
// 각 테스트의 데이터는 @Transactional에 의해 테스트 종료 후 롤백된다.
@SpringBootTest(
    properties = [
        "spring.config.import=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF",
        "custom.jwt.access-secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "custom.jwt.refresh-secret=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
        "custom.jwt.access-token-validity-seconds=1800",
        "custom.jwt.refresh-token-validity-seconds=1209600",
    ],
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
@Transactional
internal class MeetingIntegrationTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Autowired
    private lateinit var tokens: JwtTokenProvider

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var meetings: MeetingRepository

    @Autowired
    private lateinit var members: MeetingMemberRepository

    @Autowired
    private lateinit var invitations: MeetingInvitationRepository

    @Autowired
    private lateinit var schedulePolls: SchedulePollRepository

    @Autowired
    private lateinit var contentPolls: ContentPollRepository

    @Autowired
    private lateinit var expenses: ExpenseRepository

    @Autowired
    private lateinit var settlements: SettlementRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    private lateinit var host: User
    private lateinit var member: User
    private lateinit var outsider: User
    private lateinit var meeting: Meeting
    private lateinit var hostMember: MeetingMember
    private lateinit var url: String

    @BeforeEach
    fun setUp() {
        // 공통 준비 데이터만 저장소로 생성하고, 검증할 동작은 API로 호출한다.
        host = users.save(User("host@test.com", "모임장", "password"))
        member = users.save(User("member@test.com", "회원", "password"))
        outsider = users.save(User("outsider@test.com", "외부인", "password"))
        meeting = meetings.save(Meeting(host, "제주도 여행", "통합 테스트 모임"))
        hostMember = members.save(MeetingMember(meeting, host))
        url = "/api/meetings/" + meeting.id
        flushAndClear()
    }

    @Test
    fun `모임은 ID 0으로 신규 저장하고 재조회 후 감사 필드·지연 로딩·상태 변경을 유지한다`() {
        val newMeeting = Meeting(host, "새 모임", null)
        assertEquals(0L, newMeeting.id)
        meetings.saveAndFlush(newMeeting)
        val meetingId = newMeeting.id
        assertTrue(meetingId > 0L)
        flushAndClear()

        val loaded: Meeting = meetings.findById(meetingId).orElseThrow()
        assertFalse(
            entityManager.entityManagerFactory.persistenceUnitUtil
                .isLoaded(loaded, "host"),
        )
        assertEquals("모임장", loaded.host.nickname)
        assertEquals("새 모임", loaded.name)
        assertNull(loaded.description)
        assertNotNull(loaded.createdAt)
        assertNotNull(loaded.updatedAt)
        assertTrue(loaded.isHost(host.id))
        assertFalse(loaded.isHost(member.id))
        assertTrue(loaded.isActive())
        assertFalse(loaded.isCompleted())
        assertFalse(loaded.isDeleted())

        loaded.update("수정한 모임", "수정한 설명")
        loaded.complete()
        flushAndClear()
        val completed: Meeting = meetings.findById(meetingId).orElseThrow()
        assertEquals("수정한 모임", completed.name)
        assertEquals("수정한 설명", completed.description)
        assertEquals(MeetingStatus.COMPLETED, completed.status)
        assertFalse(completed.isActive())
        assertTrue(completed.isCompleted())
        assertFalse(completed.isDeleted())

        completed.softDelete()
        flushAndClear()
        val deleted: Meeting = meetings.findById(meetingId).orElseThrow()
        assertNotNull(deleted.deletedAt)
        assertFalse(deleted.isActive())
        assertFalse(deleted.isCompleted())
        assertTrue(deleted.isDeleted())
    }

    @Test
    fun `모임원은 ID 0으로 신규 저장하고 재조회 후 지연 로딩과 탈퇴·재가입을 유지한다`() {
        val newMember = MeetingMember(meeting, member)
        assertEquals(0L, newMember.id)
        members.saveAndFlush(newMember)
        val memberId = newMember.id
        assertTrue(memberId > 0L)
        flushAndClear()

        val loaded: MeetingMember = members.findById(memberId).orElseThrow()
        val persistenceUnit: PersistenceUnitUtil = entityManager.entityManagerFactory.persistenceUnitUtil
        assertFalse(persistenceUnit.isLoaded(loaded, "meeting"))
        assertFalse(persistenceUnit.isLoaded(loaded, "user"))
        assertEquals("제주도 여행", loaded.meeting.name)
        assertEquals("회원", loaded.user.nickname)
        assertTrue(loaded.isJoined())
        assertNotNull(loaded.joinedAt)
        assertNull(loaded.leftAt)

        loaded.leave()
        flushAndClear()
        val left: MeetingMember = members.findById(memberId).orElseThrow()
        assertFalse(left.isJoined())
        assertNotNull(left.leftAt)

        left.rejoin()
        flushAndClear()
        val rejoined: MeetingMember = members.findById(memberId).orElseThrow()
        assertEquals(memberId, rejoined.id)
        assertTrue(rejoined.isJoined())
        assertNotNull(rejoined.joinedAt)
        assertNull(rejoined.leftAt)
    }

    @Test
    fun `초대는 ID 0으로 신규 저장하고 재조회 후 감사 필드·지연 로딩·만료 판단을 유지한다`() {
        val expiresAt = LocalDateTime.now().plusDays(1).withNano(0)
        val invitation = MeetingInvitation(meeting, "entity-reload-code", expiresAt)
        assertEquals(0L, invitation.id)
        invitations.saveAndFlush(invitation)
        val invitationId = invitation.id
        assertTrue(invitationId > 0L)
        assertNotNull(invitation.createdAt)
        flushAndClear()

        val loaded: MeetingInvitation = invitations.findById(invitationId).orElseThrow()
        assertFalse(
            entityManager.entityManagerFactory.persistenceUnitUtil
                .isLoaded(loaded, "meeting"),
        )
        assertEquals("제주도 여행", loaded.meeting.name)
        assertEquals("entity-reload-code", loaded.inviteCode)
        assertEquals(expiresAt, loaded.expiresAt)
        assertNotNull(loaded.createdAt)
        assertFalse(loaded.isExpired())

        val expired =
            invitations.saveAndFlush(
                MeetingInvitation(meeting, "expired-entity-code", LocalDateTime.now().minusDays(1)),
            )
        val expiredId = expired.id
        flushAndClear()
        assertTrue(invitations.findById(expiredId).orElseThrow().isExpired())
    }

    // 모임 API: 생성, 조회, 수정, 종료
    @Test
    fun `모임 생성 API는 201을 반환하고 생성자를 JOINED 모임원으로 저장한다`() {
        val result: MvcResult =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/api/meetings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(host))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(MeetingCreateRequest("부산 여행", "새 모임"))),
                ).andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.success").value(true))
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(201))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.hostId").value(host.id))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.participantCount").value(1))
                .andReturn()

        val meetingId = data(result).path("id").asLong()
        flushAndClear()
        assertEquals("부산 여행", meetings.findById(meetingId).orElseThrow().name)
        val registeredHost = members.findByMeetingIdAndUserId(meetingId, host.id)
        assertNotNull(registeredHost)
        assertEquals(MeetingMemberStatus.JOINED, registeredHost.status)
        assertEquals(
            1L,
            members.countByMeetingIdAndStatus(meetingId, MeetingMemberStatus.JOINED),
        )
    }

    @Test
    fun `공백 모임 이름은 400으로 거절하고 모임을 저장하지 않는다`() {
        val before = meetings.count()
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .post("/api/meetings")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(MeetingCreateRequest("   ", "설명"))),
            ),
            400,
        )
        assertEquals(before, meetings.count())
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"name\":null}"])
    fun `생성 요청의 모임 이름이 누락되거나 null이면 기존 검증 메시지로 거절한다`(body: String) {
        val before = meetings.count()
        assertError(
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/api/meetings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(host))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(MockMvcResultMatchers.jsonPath("$.message").value("모임 이름은 필수입니다.")),
            400,
        )
        assertEquals(before, meetings.count())
    }

    @Test
    fun `100자를 초과한 모임 이름은 400으로 거절한다`() {
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .post("/api/meetings")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        jsonMapper.writeValueAsString(
                            MeetingCreateRequest(
                                "가".repeat(101),
                                "설명",
                            ),
                        ),
                    ),
            ),
            400,
        )
    }

    @Test
    fun `500자를 초과한 모임 설명은 400으로 거절한다`() {
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .post("/api/meetings")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        jsonMapper.writeValueAsString(
                            MeetingCreateRequest(
                                "모임",
                                "가".repeat(501),
                            ),
                        ),
                    ),
            ),
            400,
        )
    }

    @Test
    fun `로그인하지 않은 요청은 401로 거절한다`() {
        assertError(mvc.perform(MockMvcRequestBuilders.get("/api/meetings")), 401)
    }

    @Test
    fun `유효하지 않은 JWT 요청은 401로 거절한다`() {
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .get("/api/meetings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"),
            ),
            401,
        )
    }

    @Test
    fun `JOINED 모임원은 모임 상세 정보를 조회할 수 있다`() {
        join(member, createInvitation().path("inviteCode").asString())
        mvc
            .perform(
                MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.id").value(meeting.id))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.name").value("제주도 여행"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.participantCount").value(2))
    }

    @Test
    fun `외부인은 모임 상세 정보를 조회할 수 없다`() {
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .get(url)
                    .header(HttpHeaders.AUTHORIZATION, bearer(outsider)),
            ),
            403,
        )
    }

    @Test
    fun `존재하지 않는 모임은 404로 응답한다`() {
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .get("/api/meetings/" + Long.MAX_VALUE)
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            404,
        )
    }

    @Test
    fun `삭제된 모임은 상세 조회와 내 모임 목록에서 제외된다`() {
        meetings.findById(meeting.id).orElseThrow().softDelete()
        flushAndClear()
        assertError(
            mvc.perform(
                MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            404,
        )
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/api/meetings")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `내 모임 목록은 참여 중인 모임과 참가자 수를 반환한다`() {
        join(member, createInvitation().path("inviteCode").asString())
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/api/meetings")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.length()").value(1))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].id").value(meeting.id))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].participantCount").value(2))
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/api/meetings")
                    .header(HttpHeaders.AUTHORIZATION, bearer(outsider)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `모임장은 모임 정보를 수정할 수 있고 DB에도 반영된다`() {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .put(url)
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(MeetingUpdateRequest("변경된 이름", "변경된 설명"))),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(200))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.name").value("변경된 이름"))
        flushAndClear()
        val saved: Meeting = meetings.findById(meeting.id).orElseThrow()
        assertEquals("변경된 이름", saved.name)
        assertEquals("변경된 설명", saved.description)
    }

    @Test
    fun `일반 모임원은 모임을 수정할 수 없고 기존 정보가 유지된다`() {
        join(member, createInvitation().path("inviteCode").asString())
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .put(url)
                    .header(HttpHeaders.AUTHORIZATION, bearer(member))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(MeetingUpdateRequest("변경 시도", "설명"))),
            ),
            403,
        )
        flushAndClear()
        assertEquals("제주도 여행", meetings.findById(meeting.id).orElseThrow().name)
    }

    @Test
    fun `모임 수정 요청에도 이름 검증을 적용한다`() {
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .put(url)
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(MeetingUpdateRequest("", "설명"))),
            ),
            400,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"name\":null}"])
    fun `수정 요청의 모임 이름이 누락되거나 null이면 기존 정보와 검증 메시지를 유지한다`(body: String) {
        assertError(
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .put(url)
                        .header(HttpHeaders.AUTHORIZATION, bearer(host))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(MockMvcResultMatchers.jsonPath("$.message").value("모임 이름은 필수입니다.")),
            400,
        )
        flushAndClear()
        assertEquals("제주도 여행", meetings.findById(meeting.id).orElseThrow().name)
    }

    @Test
    fun `지출과 열린 투표가 없으면 모임을 종료하고 종료된 모임도 조회할 수 있다`() {
        completeMeeting()
        flushAndClear()
        assertEquals(
            MeetingStatus.COMPLETED,
            meetings.findById(meeting.id).orElseThrow().status,
        )
        mvc
            .perform(
                MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.status").value("COMPLETED"))
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/api/meetings")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].status").value("COMPLETED"))
    }

    @Test
    fun `외부인은 모임을 종료할 수 없고 ACTIVE 상태가 유지된다`() {
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .patch(url + "/complete")
                    .header(HttpHeaders.AUTHORIZATION, bearer(outsider)),
            ),
            403,
        )
        assertMeetingActive()
    }

    @Test
    fun `열린 일정 투표가 있으면 모임 종료를 409로 거절한다`() {
        schedulePolls.saveAndFlush(
            SchedulePoll.create(meetings.findById(meeting.id).orElseThrow(), LocalDateTime.now().plusDays(7)),
        )
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .patch(url + "/complete")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            409,
        )
        assertMeetingActive()
    }

    @Test
    fun `열린 콘텐츠 투표가 있으면 모임 종료를 409로 거절한다`() {
        contentPolls.saveAndFlush(
            ContentPoll(
                meeting.id,
                LocalDateTime.now().plusDays(7),
            ),
        )
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .patch(url + "/complete")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            409,
        )
        assertMeetingActive()
    }

    @Test
    fun `지출이 있는데 정산이 없으면 모임을 종료할 수 없다`() {
        addExpense(hostMember)
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .patch(url + "/complete")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            409,
        )
        assertMeetingActive()
    }

    @Test
    fun `지출이 있고 정산이 OPEN이면 모임을 종료할 수 없다`() {
        addExpense(hostMember)
        settlements.saveAndFlush(Settlement(meeting.id))
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .patch(url + "/complete")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            409,
        )
        assertMeetingActive()
    }

    @Test
    fun `지출이 있어도 정산이 CLOSED이면 모임을 종료할 수 있다`() {
        addExpense(hostMember)
        closeSettlement()
        completeMeeting()
        flushAndClear()
        assertEquals(
            MeetingStatus.COMPLETED,
            meetings.findById(meeting.id).orElseThrow().status,
        )
    }

    @Test
    fun `종료된 모임은 수정하거나 다시 종료할 수 없다`() {
        completeMeeting()
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .put(url)
                    .header(HttpHeaders.AUTHORIZATION, bearer(host))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(MeetingUpdateRequest("수정 시도", "설명"))),
            ),
            409,
        )
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .patch(url + "/complete")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            409,
        )
        flushAndClear()
        assertEquals("제주도 여행", meetings.findById(meeting.id).orElseThrow().name)
    }

    // 초대 API: 생성, 조회, 삭제, 가입
    @Test
    fun `모임장은 초대를 생성·목록 조회할 수 있고 외부인은 유효한 초대 정보를 조회할 수 있다`() {
        val invitation = createInvitation()
        val code: String = invitation.path("inviteCode").asString()
        flushAndClear()
        val savedInvitation = invitations.findByInviteCode(code)
        assertNotNull(savedInvitation)
        assertEquals(meeting.id, savedInvitation.meeting.id)
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(url + "/invitations")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.length()").value(1))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].inviteCode").value(code))
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/api/invitations/" + code)
                    .header(HttpHeaders.AUTHORIZATION, bearer(outsider)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.meetingId").value(meeting.id))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.alreadyJoined").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.participantCount").value(1))
    }

    @Test
    fun `일반 모임원은 초대를 생성할 수 없다`() {
        join(member, createInvitation().path("inviteCode").asString())
        val before = invitations.count()
        assertError(
            mvc.perform(
                MockMvcRequestBuilders.post(url + "/invitations").header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(member),
                ),
            ),
            403,
        )
        assertEquals(before, invitations.count())
    }

    @Test
    fun `외부인은 초대 코드 목록을 조회할 수 없다`() {
        assertError(
            mvc.perform(
                MockMvcRequestBuilders.get(url + "/invitations").header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(outsider),
                ),
            ),
            403,
        )
    }

    @Test
    fun `모임장이 초대를 삭제하면 조회와 가입이 모두 404로 거절된다`() {
        val invitation = createInvitation()
        val code: String = invitation.path("inviteCode").asString()
        val invitationId = invitation.path("id").asLong()
        mvc
            .perform(
                MockMvcRequestBuilders.delete(url + "/invitations/" + invitationId).header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(host),
                ),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(200))
        flushAndClear()
        assertFalse(invitations.existsById(invitationId))
        assertError(
            mvc.perform(
                MockMvcRequestBuilders.get("/api/invitations/" + code).header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(member),
                ),
            ),
            404,
        )
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .post("/api/invitations/" + code + "/join")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ),
            404,
        )
        assertNull(members.findByMeetingIdAndUserId(meeting.id, member.id))
    }

    @Test
    fun `모임장이 아니면 초대를 삭제할 수 없고 초대가 유지된다`() {
        val invitation = createInvitation()
        val invitationId = invitation.path("id").asLong()
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .delete(url + "/invitations/" + invitationId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(outsider)),
            ),
            403,
        )
        assertTrue(invitations.existsById(invitationId))
    }

    @Test
    fun `다른 모임 소속 초대는 404로 거절하고 삭제하지 않는다`() {
        val otherMeeting = meetings.saveAndFlush(Meeting(host, "다른 모임", ""))
        val otherInvitation =
            invitations.saveAndFlush(
                MeetingInvitation(
                    otherMeeting,
                    "other-meeting-code",
                    LocalDateTime.now().plusDays(7),
                ),
            )
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .delete(url + "/invitations/" + otherInvitation.id)
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            404,
        )
        assertTrue(invitations.existsById(otherInvitation.id))
    }

    @Test
    fun `존재하지 않는 초대의 조회와 가입은 404로 응답한다`() {
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .get("/api/invitations/missing-code")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ),
            404,
        )
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .post("/api/invitations/missing-code/join")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ),
            404,
        )
    }

    @Test
    fun `만료된 초대의 조회와 가입은 410으로 거절하고 모임원을 추가하지 않는다`() {
        invitations.saveAndFlush(
            MeetingInvitation(
                meetings.findById(meeting.id).orElseThrow(),
                "expired-code",
                LocalDateTime.now().minusDays(1),
            ),
        )
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .get("/api/invitations/expired-code")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ),
            410,
        )
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .post("/api/invitations/expired-code/join")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ),
            410,
        )
        assertNull(members.findByMeetingIdAndUserId(meeting.id, member.id))
    }

    @Test
    fun `초대 가입 API는 사용자·모임·JOINED 상태를 반환하고 실제 모임원을 저장한다`() {
        val code: String = createInvitation().path("inviteCode").asString()
        val response = join(member, code)
        flushAndClear()
        val saved = memberRecord(member)
        assertEquals(saved.id, response.path("id").asLong())
        assertEquals(meeting.id, response.path("meetingId").asLong())
        assertEquals(member.id, response.path("userId").asLong())
        assertEquals("JOINED", response.path("status").asString())
        assertEquals(MeetingMemberStatus.JOINED, saved.status)
        assertNotNull(saved.joinedAt)
        assertNull(saved.leftAt)
        assertEquals(2L, joinedCount())
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/api/invitations/" + code)
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.alreadyJoined").value(true))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.participantCount").value(2))
    }

    @Test
    fun `이미 가입한 사용자의 중복 요청은 HTTP와 본문 모두 409이고 모임원 행은 유지된다`() {
        val code: String = createInvitation().path("inviteCode").asString()
        val originalId = join(member, code).path("id").asLong()
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .post("/api/invitations/" + code + "/join")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ),
            409,
        )
        flushAndClear()
        assertEquals(originalId, memberRecord(member).id)
        assertEquals(2L, members.count())
    }

    @Test
    fun `같은 모임의 다른 초대 코드로도 중복 가입할 수 없다`() {
        val firstCode = createInvitation().path("inviteCode").asString()
        val otherCode = createInvitation().path("inviteCode").asString()
        val originalId = join(member, firstCode).path("id").asLong()
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .post("/api/invitations/" + otherCode + "/join")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ),
            409,
        )
        flushAndClear()
        assertEquals(originalId, memberRecord(member).id)
        assertEquals(2L, members.count())
    }

    @Test
    fun `서로 다른 사용자는 같은 초대 코드로 각각 가입할 수 있다`() {
        val code: String = createInvitation().path("inviteCode").asString()
        join(member, code)
        join(outsider, code)
        flushAndClear()
        assertEquals(MeetingMemberStatus.JOINED, memberRecord(member).status)
        assertEquals(MeetingMemberStatus.JOINED, memberRecord(outsider).status)
        assertEquals(3L, joinedCount())
    }

    @Test
    fun `종료된 모임은 초대 생성·초대 조회·가입·초대 삭제를 409로 거절한다`() {
        val invitation = createInvitation()
        val code: String = invitation.path("inviteCode").asString()
        completeMeeting()
        assertError(
            mvc.perform(
                MockMvcRequestBuilders.post(url + "/invitations").header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(host),
                ),
            ),
            409,
        )
        assertError(
            mvc.perform(
                MockMvcRequestBuilders.get("/api/invitations/" + code).header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(member),
                ),
            ),
            409,
        )
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .post("/api/invitations/" + code + "/join")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ),
            409,
        )
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .delete(
                        url + "/invitations/" + invitation.path("id").asLong(),
                    ).header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ),
            409,
        )
        assertNull(members.findByMeetingIdAndUserId(meeting.id, member.id))
        assertTrue(invitations.existsById(invitation.path("id").asLong()))
    }

    @Test
    fun `삭제된 모임의 초대로는 가입할 수 없다`() {
        val code: String = createInvitation().path("inviteCode").asString()
        meetings.findById(meeting.id).orElseThrow().softDelete()
        flushAndClear()
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .post("/api/invitations/" + code + "/join")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ),
            404,
        )
        assertNull(members.findByMeetingIdAndUserId(meeting.id, member.id))
    }

    // 모임원 API: 목록, 탈퇴, 재가입
    @Test
    fun `모임원 목록은 JOINED 회원만 반환하고 외부인은 조회할 수 없다`() {
        join(member, createInvitation().path("inviteCode").asString())
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(url + "/members")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.length()").value(2))
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .get(url + "/members")
                    .header(HttpHeaders.AUTHORIZATION, bearer(outsider)),
            ),
            403,
        )
        leave(member)
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(url + "/members")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.length()").value(1))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].userId").value(host.id))
    }

    @Test
    fun `일반 모임원이 탈퇴하면 LEFT와 탈퇴 시각이 저장되고 모임 조회 권한을 잃는다`() {
        join(member, createInvitation().path("inviteCode").asString())
        leave(member)
        flushAndClear()
        val left = memberRecord(member)
        assertEquals(MeetingMemberStatus.LEFT, left.status)
        assertNotNull(left.leftAt)
        assertEquals(1L, joinedCount())
        assertError(
            mvc.perform(
                MockMvcRequestBuilders
                    .get(url)
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ),
            403,
        )
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/api/meetings")
                    .header(HttpHeaders.AUTHORIZATION, bearer(member)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `탈퇴 후 재가입 API는 기존 모임원 ID를 재사용하고 탈퇴 시각을 해제한다`() {
        val code: String = createInvitation().path("inviteCode").asString()
        val originalId = join(member, code).path("id").asLong()
        leave(member)
        flushAndClear()
        assertEquals(MeetingMemberStatus.LEFT, memberRecord(member).status)
        assertNotNull(memberRecord(member).leftAt)
        val response = join(member, code)
        flushAndClear()
        val rejoined = memberRecord(member)
        assertEquals(originalId, response.path("id").asLong())
        assertEquals(originalId, rejoined.id)
        assertEquals("JOINED", response.path("status").asString())
        assertTrue(response.path("leftAt").isNull())
        assertEquals(MeetingMemberStatus.JOINED, rejoined.status)
        assertNull(rejoined.leftAt)
        assertNotNull(rejoined.joinedAt)
        assertEquals(2L, members.count())
        assertEquals(2L, joinedCount())
    }

    @Test
    fun `진행 중인 모임의 모임장은 탈퇴할 수 없다`() {
        assertError(
            mvc.perform(
                MockMvcRequestBuilders.delete(url + "/members/me").header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(host),
                ),
            ),
            409,
        )
        flushAndClear()
        assertEquals(MeetingMemberStatus.JOINED, memberRecord(host).status)
        assertNull(memberRecord(host).leftAt)
    }

    @Test
    fun `종료된 모임의 모임장은 탈퇴할 수 있다`() {
        completeMeeting()
        leave(host)
        flushAndClear()
        assertEquals(MeetingMemberStatus.LEFT, memberRecord(host).status)
        assertNotNull(memberRecord(host).leftAt)
    }

    @Test
    fun `가입한 적 없는 사용자의 탈퇴 요청은 404로 거절한다`() {
        assertError(
            mvc.perform(
                MockMvcRequestBuilders.delete(url + "/members/me").header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(outsider),
                ),
            ),
            404,
        )
        assertEquals(1L, joinedCount())
    }

    @Test
    fun `이미 탈퇴한 사용자는 다시 탈퇴할 수 없고 최초 탈퇴 시각이 유지된다`() {
        join(member, createInvitation().path("inviteCode").asString())
        leave(member)
        flushAndClear()
        val originalLeftAt = memberRecord(member).leftAt
        assertError(
            mvc.perform(
                MockMvcRequestBuilders.delete(url + "/members/me").header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(member),
                ),
            ),
            409,
        )
        flushAndClear()
        assertEquals(originalLeftAt, memberRecord(member).leftAt)
        assertEquals(MeetingMemberStatus.LEFT, memberRecord(member).status)
    }

    @Test
    fun `미정산 지출에 포함된 모임원은 탈퇴할 수 없고 가입 상태가 유지된다`() {
        join(member, createInvitation().path("inviteCode").asString())
        addExpense(memberRecord(member))
        assertError(
            mvc.perform(
                MockMvcRequestBuilders.delete(url + "/members/me").header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(member),
                ),
            ),
            409,
        )
        flushAndClear()
        assertEquals(MeetingMemberStatus.JOINED, memberRecord(member).status)
        assertNull(memberRecord(member).leftAt)
    }

    @Test
    fun `정산이 CLOSED이면 지출 참여자도 탈퇴할 수 있다`() {
        join(member, createInvitation().path("inviteCode").asString())
        addExpense(memberRecord(member))
        closeSettlement()
        leave(member)
        flushAndClear()
        assertEquals(MeetingMemberStatus.LEFT, memberRecord(member).status)
        assertNotNull(memberRecord(member).leftAt)
    }

    // 반복되는 JWT 발급, 성공 요청, DB 재조회 준비만 보조 메서드로 모은다.
    private fun bearer(user: User): String = "Bearer " + tokens.createAccessToken(user.id)

    private fun data(result: MvcResult): JsonNode = jsonMapper.readTree(result.response.contentAsByteArray).path("data")

    private fun createInvitation(): JsonNode {
        val result: MvcResult =
            mvc
                .perform(
                    MockMvcRequestBuilders.post(url + "/invitations").header(
                        HttpHeaders.AUTHORIZATION,
                        bearer(host),
                    ),
                ).andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(201))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.inviteCode").isNotEmpty())
                .andReturn()
        return data(result)
    }

    private fun join(
        user: User,
        code: String,
    ): JsonNode {
        val result: MvcResult =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/api/invitations/" + code + "/join")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)),
                ).andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.success").value(true))
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(200))
                .andReturn()
        return data(result)
    }

    private fun leave(user: User) {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .delete(url + "/members/me")
                    .header(HttpHeaders.AUTHORIZATION, bearer(user)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(200))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.status").value("LEFT"))
    }

    private fun completeMeeting() {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .patch(url + "/complete")
                    .header(HttpHeaders.AUTHORIZATION, bearer(host)),
            ).andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(200))
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.status").value("COMPLETED"))
    }

    private fun assertError(
        result: ResultActions,
        code: Int,
    ) {
        result
            .andExpect(MockMvcResultMatchers.status().`is`(code))
            .andExpect(MockMvcResultMatchers.jsonPath("$.success").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(code))
    }

    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    private fun memberRecord(user: User): MeetingMember {
        val membership = members.findByMeetingIdAndUserId(meeting.id, user.id)
        assertNotNull(membership)
        return membership
    }

    private fun joinedCount(): Long = members.countByMeetingIdAndStatus(meeting.id, MeetingMemberStatus.JOINED)

    private fun assertMeetingActive() {
        flushAndClear()
        assertEquals(
            MeetingStatus.ACTIVE,
            meetings.findById(meeting.id).orElseThrow().status,
        )
    }

    private fun addExpense(payer: MeetingMember) {
        expenses.saveAndFlush(
            Expense(
                meetingId = meeting.id,
                payerMemberId = payer.id,
                title = "식사",
                amount = 1000L,
                memo = null,
                participantIds = setOf(payer.id),
            ),
        )
    }

    private fun closeSettlement() {
        val settlement = Settlement(meeting.id)
        settlement.close(hostMember.id)
        settlements.saveAndFlush(settlement)
    }

    companion object {
        @Container
        @JvmField
        val mysql: MySQLContainer =
            MySQLContainer("mysql:8.0").withDatabaseName("meeting_api_test")

        @DynamicPropertySource
        @JvmStatic
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
        }
    }
}
