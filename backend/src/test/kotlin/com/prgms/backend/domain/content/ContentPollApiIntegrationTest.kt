package com.prgms.backend.domain.content

import com.prgms.backend.domain.content.entity.ContentCandidate
import com.prgms.backend.domain.content.entity.ContentPoll
import com.prgms.backend.domain.content.entity.ContentVote
import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.enums.ContentPreference
import com.prgms.backend.domain.content.repository.ContentCandidateRepository
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.content.repository.ContentVoteRepository
import com.prgms.backend.domain.content.service.ContentPollCloseScheduler
import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.security.JwtTokenProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.LocalDateTime
import java.util.UUID

// 보안 필터(JWT) → 컨트롤러 → 서비스 → MySQL → 예외 핸들러까지 실제로 연결해 HTTP 응답 코드를 검증
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
class ContentPollApiIntegrationTest {
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
    private lateinit var pollRepository: ContentPollRepository

    @Autowired
    private lateinit var candidateRepository: ContentCandidateRepository

    @Autowired
    private lateinit var voteRepository: ContentVoteRepository

    private lateinit var host: User
    private lateinit var alice: User
    private lateinit var outsider: User
    private var meetingId: Long = 0
    private lateinit var aliceMember: MeetingMember
    private lateinit var base: String

    @BeforeEach
    fun setUp() {
        host = saveUser("host")
        alice = saveUser("alice")
        outsider = saveUser("outsider")
        val meeting = meetingRepository.save(Meeting(host, "API 통합 테스트", ""))
        meetingId = meeting.id
        meetingMemberRepository.save(MeetingMember(meeting, host))
        aliceMember = meetingMemberRepository.save(MeetingMember(meeting, alice))
        base = "/api/meetings/$meetingId"
    }

    // ---------- 인증 ----------

    @Test
    fun `토큰이 없으면 모든 콘텐츠 API가 401`() {
        mvc.perform(get("$base/content-poll")).andExpect(status().isUnauthorized())
        mvc
            .perform(post("$base/content-poll").contentType(MediaType.APPLICATION_JSON).content(deadlineJson(1)))
            .andExpect(status().isUnauthorized())
        mvc.perform(get("$base/content-poll/results")).andExpect(status().isUnauthorized())
        mvc
            .perform(put("$base/content-votes").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized())
    }

    @Test
    fun `탈퇴한 회원의 토큰은 발급 이후라도 401`() {
        openPoll()
        alice.updateStatus(UserStatus.WITHDRAWN)
        alice.updateDeletedAt(LocalDateTime.now())
        userRepository.save(alice)

        mvc.perform(auth(get("$base/content-poll"), alice)).andExpect(status().isUnauthorized())
    }

    // ---------- 투표판 ----------

    @Test
    fun `투표판 생성 - 비모임장 403, 과거 마감시간 400, 모임장 201, 중복 409`() {
        mvc
            .perform(
                auth(post("$base/content-poll"), alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(deadlineJson(1)),
            ).andExpect(status().isForbidden())
        mvc
            .perform(
                auth(post("$base/content-poll"), host)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(deadlineJson(-1)),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
        mvc
            .perform(
                auth(post("$base/content-poll"), host)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(deadlineJson(1)),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("OPEN"))

        mvc
            .perform(
                auth(post("$base/content-poll"), host)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(deadlineJson(2)),
            ).andExpect(status().isConflict())
    }

    // ---------- 후보 ----------

    @Test
    fun `후보 - 생성 201, 중복 제목 409, 제목 누락 400, 수정 200, 삭제 204`() {
        openPoll()
        val path = "$base/content-poll/candidates"

        mvc
            .perform(
                auth(post(path), alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"title":"영화","description":"설명"}"""),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.title").value("영화"))
        mvc
            .perform(
                auth(post(path), host)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"title":"영화"}"""),
            ).andExpect(status().isConflict())
        mvc
            .perform(
                auth(post(path), host)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"title":""}"""),
            ).andExpect(status().isBadRequest())

        val saved = candidateRepository.findAll().first { it.title == "영화" }
        mvc
            .perform(
                auth(patch("$path/${saved.id}"), host)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"title":"뺏기"}"""),
            ).andExpect(status().isForbidden())
        mvc
            .perform(
                auth(patch("$path/${saved.id}"), alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"title":"드라마"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("드라마"))
        mvc.perform(auth(delete("$path/${saved.id}"), alice)).andExpect(status().isNoContent())
        mvc.perform(auth(delete("$path/${saved.id}"), alice)).andExpect(status().isNotFound())
    }

    // ---------- 투표 ----------

    @Test
    fun `투표 - 200, 같은 후보 재투표는 선택만 변경, 본문 누락 400, 마감 후 409`() {
        val poll = openPoll()
        val candidate = candidate("A", aliceMember)
        val path = "$base/content-votes"

        mvc
            .perform(
                auth(put(path), alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(voteJson(candidate.id, "PREFER")),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.createdAt").exists())
            .andExpect(jsonPath("$.data.updatedAt").exists())
            .andExpect(jsonPath("$.data.updateAd").doesNotExist())
        mvc
            .perform(
                auth(put(path), alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(voteJson(candidate.id, "DISLIKE")),
            ).andExpect(status().isOk())
        assertEquals(1, voteRepository.findByContentCandidate_ContentPoll_Id(poll.id).size)

        mvc
            .perform(
                auth(put(path), alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"candidateId":${candidate.id}}"""),
            ).andExpect(status().isBadRequest())
        mvc
            .perform(
                auth(put(path), alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(voteJson(99999L, "PREFER")),
            ).andExpect(status().isNotFound())

        close(poll)
        mvc
            .perform(
                auth(put(path), alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(voteJson(candidate.id, "PREFER")),
            ).andExpect(status().isConflict())
    }

    // ---------- 결과 / 확정 ----------

    @Test
    fun `결과 - 진행 중 409, 마감 후 200이며 정렬 파라미터 적용`() {
        val poll = openPoll()
        val candidate = candidate("A", aliceMember)
        voteRepository.save(ContentVote(candidate, aliceMember.id, ContentPreference.PREFER))
        val path = "$base/content-poll/results"

        mvc.perform(auth(get(path), alice)).andExpect(status().isConflict())

        close(poll)
        mvc
            .perform(auth(get(path), alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.candidates[0].responseCount").value(1))
            .andExpect(jsonPath("$.data.candidates[0].noResponseCount").value(1))
        mvc.perform(auth(get(path).param("sort", "PARTICIPANTS"), alice)).andExpect(status().isOk())
        mvc.perform(auth(get(path), outsider)).andExpect(status().isNotFound())
    }

    @Test
    fun `확정 - 진행 중 409, 비모임장 403, 모임장 200, 재확정 409, 후보 누락 400`() {
        val poll = openPoll()
        val candidate = candidate("A", aliceMember)
        val path = "$base/content-poll/confirm"
        val body = """{"candidateId":${candidate.id}}"""

        mvc
            .perform(auth(post(path), host).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())

        close(poll)
        mvc
            .perform(auth(post(path), alice).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden())
        mvc
            .perform(auth(post(path), host).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
        mvc
            .perform(auth(post(path), host).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
        mvc
            .perform(auth(post(path), host).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())

        assertEquals(
            candidate.id,
            pollRepository.findById(poll.id).orElseThrow().confirmedCandidateId,
        )
        assertEquals(ContentPollStatus.CLOSED, pollRepository.findById(poll.id).orElseThrow().status)
    }

    // ---------- helpers ----------

    private fun auth(
        request: MockHttpServletRequestBuilder,
        user: User,
    ): MockHttpServletRequestBuilder =
        request.header(
            HttpHeaders.AUTHORIZATION,
            "Bearer " + jwt.createAccessToken(user.id),
        )

    private fun deadlineJson(plusDays: Int): String =
        """{"deadline":"${LocalDateTime.now().plusDays(plusDays.toLong()).withNano(0)}"}"""

    private fun voteJson(
        candidateId: Long,
        preference: String,
    ): String = """{"candidateId":$candidateId,"preference":"$preference"}"""

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

    private fun saveUser(prefix: String): User {
        val suffix = UUID.randomUUID().toString()
        return userRepository.save(User("$prefix-$suffix@example.com", "$prefix-$suffix", "password"))
    }

    companion object {
        @Container
        @JvmField
        val MYSQL: MySQLContainer = MySQLContainer("mysql:8.0").withDatabaseName("content_api_test")

        @DynamicPropertySource
        @JvmStatic
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { MYSQL.jdbcUrl }
            registry.add("spring.datasource.username") { MYSQL.username }
            registry.add("spring.datasource.password") { MYSQL.password }
        }
    }
}
