package com.prgms.backend.domain.schedule.integration

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.schedule.candidate.entity.ScheduleCandidate
import com.prgms.backend.domain.schedule.candidate.repository.ScheduleCandidateRepository
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.schedule.vote.entity.SchedulePreference
import com.prgms.backend.domain.schedule.vote.entity.ScheduleVote
import com.prgms.backend.domain.schedule.vote.repository.ScheduleVoteRepository
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.global.exception.BusinessException
import com.prgms.backend.global.exception.custom.meeting.MeetingHostRequiredException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberAlreadyLeftException
import com.prgms.backend.global.exception.custom.meeting.MeetingMemberNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotActiveException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import com.prgms.backend.global.exception.custom.schedule.DuplicateScheduleCandidateException
import com.prgms.backend.global.exception.custom.schedule.ScheduleCandidateHasVotesException
import com.prgms.backend.global.exception.custom.schedule.ScheduleCandidateLimitExceededException
import com.prgms.backend.global.exception.custom.schedule.ScheduleCandidateNotFoundException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollAlreadyExistsException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollClosedException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollNotClosedException
import com.prgms.backend.global.exception.custom.schedule.SchedulePollNotFoundException
import com.prgms.backend.security.JwtTokenProvider
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.MethodArgumentNotValidException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@SpringBootTest(
    properties = [
        "spring.config.import=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
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
class ScheduleControllerIntegrationTest {
    companion object {
        @Container
        @JvmField
        val MYSQL =
            MySQLContainer("mysql:8.0")
                .withDatabaseName("schedule_controller_test")

        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", { MYSQL.jdbcUrl })
            registry.add("spring.datasource.username", { MYSQL.username })
            registry.add("spring.datasource.password", { MYSQL.password })
        }
    }

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
    private lateinit var polls: SchedulePollRepository

    @Autowired
    private lateinit var candidates: ScheduleCandidateRepository

    @Autowired
    private lateinit var votes: ScheduleVoteRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    private lateinit var host: User
    private lateinit var member: User
    private lateinit var outsider: User
    private lateinit var meeting: Meeting
    private lateinit var joinedMember: MeetingMember
    private lateinit var url: String
    private val deadline = LocalDateTime.now().plusDays(7).withNano(0)
    private val candidateDate = LocalDate.now().plusDays(1)

    @BeforeEach
    fun setUp() {
        val suffix = UUID.randomUUID().toString()
        host = users.save(User("host-" + suffix + "@example.com", "host-" + suffix, "password"))
        member = users.save(User("member-" + suffix + "@example.com", "member-" + suffix, "password"))
        outsider = users.save(User("outsider-" + suffix + "@example.com", "outsider-" + suffix, "password"))
        meeting = meetings.save(Meeting(host, "일정 통합 테스트", ""))
        members.save(MeetingMember(meeting, host))
        joinedMember = members.save(MeetingMember(meeting, member))
        url = "/api/meetings/" + meeting.id + "/schedule-poll"
    }

    @Test
    fun `저장 후 재조회한 일정 엔티티의 연관관계와 후보 컬렉션이 동작한다`() {
        val poll = polls.save(SchedulePoll.create(meeting, deadline))
        val candidate = candidates.save(poll.addCandidate(candidateDate))
        val vote = votes.save(ScheduleVote.create(candidate, joinedMember, SchedulePreference.PREFER))
        val pollId = poll.id
        val voteId = vote.id
        assertTrue(pollId > 0)
        assertTrue(candidate.id > 0)
        assertTrue(voteId > 0)

        flushAndClear()
        val reloadedVote = checkNotNull(votes.findByIdOrNull(voteId))
        assertEquals(meeting.id, reloadedVote.scheduleCandidate.schedulePoll.meeting.id)
        assertEquals(member.id, reloadedVote.meetingMember.user.id)
        assertEquals(3, reloadedVote.getScore())
        assertNotNull(reloadedVote.createdAt)
        assertNotNull(reloadedVote.updatedAt)
        assertNotNull(reloadedVote.scheduleCandidate.createdAt)

        val reloaded = checkNotNull(polls.findByIdOrNull(pollId))
        val snapshot = reloaded.getCandidates()
        assertEquals(1, snapshot.size)
        val added = reloaded.addCandidate(candidateDate.plusDays(1))
        assertEquals(1, snapshot.size)
        entityManager.flush()
        val addedId = added.id
        assertTrue(addedId > 0)
        flushAndClear()

        val withAdded = checkNotNull(polls.findByIdOrNull(pollId))
        assertEquals(2, withAdded.getCandidates().size)
        withAdded.removeCandidate(withAdded.findCandidate(addedId))
        flushAndClear()
        assertFalse(candidates.existsById(addedId))
        assertEquals(
            1,
            checkNotNull(polls.findByIdOrNull(pollId))
                .getCandidates()
                .size,
        )
    }

    @Nested
    @DisplayName("SchedulePollController")
    inner class PollController {
        @Test
        fun `모임장은 투표소를 생성하고 201 응답을 받는다`() {
            val result =
                perform(post(url), host, mapOf("deadline" to deadline))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(201))
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.meetingId").value(meeting.id))
                    .andExpect(
                        jsonPath("$.data.deadline").value(deadline.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)),
                    ).andExpect(jsonPath("$.data.status").value("OPEN"))
                    .andReturn()

            flushAndClear()
            val saved = checkNotNull(polls.findByMeetingId(meeting.id))
            val responseId =
                jsonMapper
                    .readTree(result.response.contentAsString)
                    .path("data")
                    .path("id")
                    .asLong()
            assertEquals(saved.id, responseId)
            assertEquals(deadline, saved.deadline)
        }

        @Test
        fun `참여자는 후보를 포함한 투표소를 조회한다`() {
            val poll = createPollFixture()
            val candidate = addCandidate(poll, candidateDate)
            perform(get(url), member, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(poll.id))
                .andExpect(jsonPath("$.data.candidates", hasSize<Any>(1)))
                .andExpect(jsonPath("$.data.candidates[0].id").value(candidate.id))
                .andExpect(jsonPath("$.data.candidates[0].candidateDate").value(candidateDate.toString()))
        }

        @Test
        fun `모임장은 마감 시간을 수정하고 DB에 반영한다`() {
            createPollFixture()
            val updated = deadline.plusDays(1)
            perform(patch(url), host, mapOf("deadline" to updated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deadline").value(updated.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
            flushAndClear()
            assertEquals(updated, checkNotNull(polls.findByMeetingId(meeting.id)).deadline)
        }

        @ParameterizedTest
        @ValueSource(strings = ["POST", "PATCH"])
        fun `일반 참여자는 투표소 생성과 마감 시간 수정을 할 수 없다`(method: String) {
            createPollFixture()
            expectBusinessError(
                perform(
                    request(HttpMethod.valueOf(method), url),
                    member,
                    mapOf("deadline" to deadline.plusDays(1)),
                ),
                MeetingHostRequiredException(),
            )
        }

        @ParameterizedTest
        @ValueSource(strings = ["POST", "PATCH"])
        fun `마감 시간이 누락되거나 과거이면 400으로 거절한다`(method: String) {
            createPollFixture()
            expectValidationError(
                perform(request(HttpMethod.valueOf(method), url), host, emptyMap<String, Any?>()),
                "deadline",
                "마감 시간은 필수입니다.",
            )
            expectValidationError(
                perform(
                    request(HttpMethod.valueOf(method), url),
                    host,
                    mapOf("deadline" to null),
                ),
                "deadline",
                "마감 시간은 필수입니다.",
            )
            expectValidationError(
                perform(
                    request(HttpMethod.valueOf(method), url),
                    host,
                    mapOf("deadline" to LocalDateTime.now().minusDays(1)),
                ),
                "deadline",
                "마감 시간은 현재보다 이후여야 합니다.",
            )
            assertEquals(deadline, checkNotNull(polls.findByMeetingId(meeting.id)).deadline)
        }

        @Test
        fun `투표소를 중복 생성하면 409를 반환한다`() {
            createPollFixture()
            expectBusinessError(
                perform(post(url), host, mapOf("deadline" to deadline)),
                SchedulePollAlreadyExistsException(meeting.id),
            )
        }

        @Test
        fun `종료된 모임에는 일정 투표를 생성할 수 없다`() {
            meeting.complete()
            expectBusinessError(
                perform(post(url), host, mapOf("deadline" to deadline)),
                MeetingNotActiveException(meeting.id),
            )
            assertFalse(polls.existsByMeetingId(meeting.id))
        }

        @Test
        fun `투표소가 없으면 404를 반환한다`() {
            expectBusinessError(
                perform(get(url), member, null),
                SchedulePollNotFoundException(meeting.id),
            )
        }

        @Test
        fun `존재하지 않는 모임은 404를 반환한다`() {
            expectBusinessError(
                perform(get("/api/meetings/9223372036854775807/schedule-poll"), host, null),
                MeetingNotFoundException(Long.MAX_VALUE),
            )
        }

        @Test
        fun `모임에 가입하지 않은 사용자는 조회할 수 없다`() {
            createPollFixture()
            expectBusinessError(
                perform(get(url), outsider, null),
                MeetingMemberNotFoundException(meeting.id, outsider.id),
            )
        }

        @Test
        fun `탈퇴한 참여자는 조회할 수 없다`() {
            createPollFixture()
            joinedMember.leave()
            expectBusinessError(
                perform(get(url), member, null),
                MeetingMemberAlreadyLeftException(meeting.id, member.id),
            )
        }

        @Test
        fun `마감한 투표소의 마감 시간은 수정할 수 없다`() {
            createPollFixture().close()
            expectBusinessError(
                perform(patch(url), host, mapOf("deadline" to deadline.plusDays(1))),
                SchedulePollClosedException(),
            )
        }

        @Test
        fun `마감 전에는 결과를 조회할 수 없다`() {
            createPollFixture()
            expectBusinessError(
                perform(get(url + "/results"), member, null),
                SchedulePollNotClosedException(),
            )
        }

        @Test
        fun `마감 결과는 공동 순위·0점 응답·미응답을 구분하고 탈퇴자의 표를 제외한다`() {
            val poll = createPollFixture()
            // 등록 순서와 점수·날짜 순서를 다르게 해 실제 정렬 계약도 검증한다.
            val unanswered = addCandidate(poll, candidateDate.plusDays(2))
            val second = addCandidate(poll, candidateDate.plusDays(1))
            val first = addCandidate(poll, candidateDate)
            val hostMember = members.findByMeetingIdAndUserId(meeting.id, host.id)
            assertNotNull(hostMember)
            val thirdUser = users.save(User("third-" + host.email, "third", "password"))
            members.save(MeetingMember(meeting, thirdUser))
            // 외부 사용자가 가입·응답 후 탈퇴한 상황이다.
            val leftMember = members.save(MeetingMember(meeting, outsider))
            votes.saveAll(
                listOf(
                    ScheduleVote.create(first, checkNotNull(hostMember), SchedulePreference.PREFER),
                    ScheduleVote.create(first, joinedMember, SchedulePreference.IMPOSSIBLE),
                    ScheduleVote.create(second, checkNotNull(hostMember), SchedulePreference.AVAILABLE),
                    ScheduleVote.create(second, joinedMember, SchedulePreference.DISLIKE),
                    ScheduleVote.create(unanswered, leftMember, SchedulePreference.PREFER),
                ),
            )
            leftMember.leave()
            poll.close()
            flushAndClear()

            perform(get(url + "/results"), member, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateRanks", hasSize<Any>(3)))
                .andExpect(jsonPath("$.data.candidateRanks[*].rank").value(contains(1, 1, 3)))
                .andExpect(jsonPath("$.data.candidateRanks[*].totalScore").value(contains(3, 3, 0)))
                .andExpect(jsonPath("$.data.candidateRanks[0].candidateId").value(first.id))
                .andExpect(jsonPath("$.data.candidateRanks[0].responseCount").value(2))
                .andExpect(jsonPath("$.data.candidateRanks[0].nonResponseCount").value(1))
                .andExpect(jsonPath("$.data.candidateRanks[0].preferenceCounts.prefer").value(1))
                .andExpect(jsonPath("$.data.candidateRanks[0].preferenceCounts.impossible").value(1))
                .andExpect(jsonPath("$.data.candidateRanks[1].candidateId").value(second.id))
                .andExpect(jsonPath("$.data.candidateRanks[1].preferenceCounts.available").value(1))
                .andExpect(jsonPath("$.data.candidateRanks[1].preferenceCounts.dislike").value(1))
                .andExpect(jsonPath("$.data.candidateRanks[2].candidateId").value(unanswered.id))
                .andExpect(jsonPath("$.data.candidateRanks[2].responseCount").value(0))
                .andExpect(jsonPath("$.data.candidateRanks[2].nonResponseCount").value(3))
                .andExpect(jsonPath("$.data.participantResponses", hasSize<Any>(3)))
                .andExpect(jsonPath("$.data.participantResponses[1].userId").value(member.id))
                .andExpect(jsonPath("$.data.participantResponses[0].answers[0].preference").value("PREFER"))
                .andExpect(jsonPath("$.data.participantResponses[1].answers[0].preference").value("IMPOSSIBLE"))
                .andExpect(jsonPath("$.data.participantResponses[1].answers[1].preference").value("DISLIKE"))
                .andExpect(jsonPath("$.data.participantResponses[1].answers[2].preference").value(nullValue()))
                .andExpect(jsonPath("$.data.participantResponses[2].userId").value(thirdUser.id))
                .andExpect(jsonPath("$.data.participantResponses[2].answers[0].preference").value(nullValue()))
        }

        @Test
        fun `외부 사용자와 탈퇴한 참여자는 마감 결과를 조회할 수 없다`() {
            createPollFixture().close()
            expectBusinessError(
                perform(get(url + "/results"), outsider, null),
                MeetingMemberNotFoundException(meeting.id, outsider.id),
            )
            joinedMember.leave()
            expectBusinessError(
                perform(get(url + "/results"), member, null),
                MeetingMemberAlreadyLeftException(meeting.id, member.id),
            )
        }
    }

    @Nested
    @DisplayName("ScheduleCandidateController")
    inner class CandidateController {
        private lateinit var poll: SchedulePoll
        private lateinit var candidate: ScheduleCandidate
        private lateinit var candidateUrl: String

        @BeforeEach
        fun setUpCandidate() {
            poll = createPollFixture()
            candidate = addCandidate(poll, candidateDate)
            candidateUrl = url + "/candidates/" + candidate.id
        }

        @Test
        fun `모임장은 후보를 추가하고 201 응답을 받는다`() {
            val next = candidateDate.plusDays(1)
            val result =
                perform(post(url + "/candidates"), host, mapOf("candidateDate" to next))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(201))
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.candidateDate").value(next.toString()))
                    .andReturn()
            flushAndClear()
            val responseId =
                jsonMapper
                    .readTree(result.response.contentAsString)
                    .path("data")
                    .path("id")
                    .asLong()
            val saved = checkNotNull(candidates.findByIdOrNull(responseId))
            assertEquals(next, saved.candidateDate)
            assertEquals(poll.id, saved.schedulePoll.id)
            assertEquals(2, checkNotNull(polls.findByMeetingIdWithCandidates(meeting.id)).getCandidates().size)
        }

        @Test
        fun `모임장은 투표 없는 후보를 수정한다`() {
            val updated = candidateDate.plusDays(2)
            perform(patch(candidateUrl), host, mapOf("candidateDate" to updated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(candidate.id))
                .andExpect(jsonPath("$.data.candidateDate").value(updated.toString()))
            flushAndClear()
            assertEquals(updated, checkNotNull(candidates.findByIdOrNull(candidate.id)).candidateDate)
        }

        @Test
        fun `모임장은 후보를 삭제하고 본문 없는 204 응답을 받는다`() {
            perform(delete(candidateUrl), host, null)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
            flushAndClear()
            assertFalse(candidates.existsById(candidate.id))
        }

        @ParameterizedTest
        @ValueSource(strings = ["POST", "PATCH", "DELETE"])
        fun `일반 참여자는 후보 추가·수정·삭제를 할 수 없다`(method: String) {
            val target = if (method == "POST") url + "/candidates" else candidateUrl
            expectBusinessError(
                perform(
                    request(HttpMethod.valueOf(method), target),
                    member,
                    mapOf("candidateDate" to candidateDate.plusDays(1)),
                ),
                MeetingHostRequiredException(),
            )
        }

        @ParameterizedTest
        @ValueSource(strings = ["POST", "PATCH"])
        fun `날짜 누락, 오늘, 과거 날짜를 거절한다`(method: String) {
            val target = if (method == "POST") url + "/candidates" else candidateUrl
            expectValidationError(
                perform(request(HttpMethod.valueOf(method), target), host, emptyMap<String, Any?>()),
                "candidateDate",
                "날짜는 필수입니다",
            )
            expectValidationError(
                perform(
                    request(HttpMethod.valueOf(method), target),
                    host,
                    mapOf("candidateDate" to null),
                ),
                "candidateDate",
                "날짜는 필수입니다",
            )
            expectValidationError(
                perform(
                    request(HttpMethod.valueOf(method), target),
                    host,
                    mapOf("candidateDate" to LocalDate.now()),
                ),
                "candidateDate",
                "후보 날짜는 오늘보다 이후여야 합니다.",
            )
            expectValidationError(
                perform(
                    request(HttpMethod.valueOf(method), target),
                    host,
                    mapOf("candidateDate" to LocalDate.now().minusDays(1)),
                ),
                "candidateDate",
                "후보 날짜는 오늘보다 이후여야 합니다.",
            )
            assertEquals(candidateDate, checkNotNull(candidates.findByIdOrNull(candidate.id)).candidateDate)
        }

        @Test
        fun `중복 날짜의 후보 추가와 수정을 거절한다`() {
            expectBusinessError(
                perform(post(url + "/candidates"), host, mapOf("candidateDate" to candidateDate)),
                DuplicateScheduleCandidateException(candidateDate),
            )
            val other = addCandidate(poll, candidateDate.plusDays(1))
            expectBusinessError(
                perform(
                    patch(url + "/candidates/" + other.id),
                    host,
                    mapOf("candidateDate" to candidateDate),
                ),
                DuplicateScheduleCandidateException(candidateDate),
            )
        }

        @Test
        fun `열한 번째 후보를 추가하면 409를 반환한다`() {
            for (i in 1 until 10) {
                addCandidate(poll, candidateDate.plusDays(i.toLong()))
            }
            expectBusinessError(
                perform(
                    post(url + "/candidates"),
                    host,
                    mapOf("candidateDate" to candidateDate.plusDays(10)),
                ),
                ScheduleCandidateLimitExceededException(10),
            )
        }

        @ParameterizedTest
        @ValueSource(strings = ["PATCH", "DELETE"])
        fun `투표가 있는 후보는 수정·삭제할 수 없다`(method: String) {
            votes.save(ScheduleVote.create(candidate, joinedMember, SchedulePreference.AVAILABLE))
            expectBusinessError(
                perform(
                    request(HttpMethod.valueOf(method), candidateUrl),
                    host,
                    mapOf("candidateDate" to candidateDate.plusDays(1)),
                ),
                ScheduleCandidateHasVotesException(candidate.id),
            )
            assertTrue(candidates.existsById(candidate.id))
            assertEquals(candidateDate, checkNotNull(candidates.findByIdOrNull(candidate.id)).candidateDate)
        }

        @ParameterizedTest
        @ValueSource(strings = ["POST", "PATCH", "DELETE"])
        fun `마감된 투표소의 후보는 변경할 수 없다`(method: String) {
            poll.close()
            val target = if (method == "POST") url + "/candidates" else candidateUrl
            expectBusinessError(
                perform(
                    request(HttpMethod.valueOf(method), target),
                    host,
                    mapOf("candidateDate" to candidateDate.plusDays(1)),
                ),
                SchedulePollClosedException(),
            )
        }

        @Test
        fun `다른 모임의 후보 ID로 수정·삭제할 수 없다`() {
            val otherMeeting = meetings.save(Meeting(host, "다른 모임", ""))
            val otherPoll = polls.save(SchedulePoll.create(otherMeeting, deadline))
            val other = addCandidate(otherPoll, candidateDate)
            val target = url + "/candidates/" + other.id
            expectBusinessError(
                perform(patch(target), host, mapOf("candidateDate" to candidateDate.plusDays(1))),
                ScheduleCandidateNotFoundException(other.id),
            )
            expectBusinessError(
                perform(delete(target), host, null),
                ScheduleCandidateNotFoundException(other.id),
            )
        }
    }

    @Nested
    @DisplayName("ScheduleVoteController")
    inner class VoteController {
        private lateinit var poll: SchedulePoll
        private lateinit var candidate: ScheduleCandidate
        private lateinit var voteUrl: String

        private fun givenPoll(pollDeadline: LocalDateTime) {
            poll = polls.save(SchedulePoll.create(meeting, pollDeadline))
            candidate = addCandidate(poll, candidateDate)
            voteUrl = url + "/candidates/" + candidate.id + "/vote"
        }

        @ParameterizedTest
        @EnumSource(SchedulePreference::class)
        fun `참여자는 각 선호도로 투표하고 200 응답을 받는다`(preference: SchedulePreference) {
            givenPoll(deadline)
            perform(put(voteUrl), member, mapOf("preference" to preference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.candidateId").value(candidate.id))
                .andExpect(jsonPath("$.data.meetingMemberId").value(joinedMember.id))
                .andExpect(jsonPath("$.data.preference").value(preference.name))
            flushAndClear()
            assertEquals(
                preference,
                checkNotNull(
                    votes.findByScheduleCandidateIdAndMeetingMemberId(candidate.id, joinedMember.id),
                ).preference,
            )
        }

        @Test
        fun `재투표하면 기존 투표를 수정하고 행을 중복 생성하지 않는다`() {
            givenPoll(deadline)
            perform(put(voteUrl), member, mapOf("preference" to "PREFER")).andExpect(status().isOk())
            flushAndClear()
            val originalId =
                checkNotNull(
                    votes.findByScheduleCandidateIdAndMeetingMemberId(candidate.id, joinedMember.id),
                ).id
            perform(put(voteUrl), member, mapOf("preference" to "IMPOSSIBLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preference").value("IMPOSSIBLE"))
            flushAndClear()
            val saved =
                checkNotNull(
                    votes.findByScheduleCandidateIdAndMeetingMemberId(candidate.id, joinedMember.id),
                )
            assertEquals(originalId, saved.id)
            assertEquals(SchedulePreference.IMPOSSIBLE, saved.preference)
            assertEquals(1, votes.findAllByScheduleCandidateSchedulePollId(poll.id).size)
        }

        @Test
        fun `선호도가 누락되면 400으로 거절하고 저장하지 않는다`() {
            givenPoll(deadline)
            expectValidationError(perform(put(voteUrl), member, emptyMap<String, Any?>()), "preference", "선호도는 필수입니다.")
            expectValidationError(
                perform(
                    put(voteUrl),
                    member,
                    mapOf("preference" to null),
                ),
                "preference",
                "선호도는 필수입니다.",
            )
            assertFalse(votes.existsByScheduleCandidateId(candidate.id))
        }

        @Test
        fun `정의되지 않은 선호도는 400으로 거절하고 저장하지 않는다`() {
            givenPoll(deadline)
            perform(put(voteUrl), member, mapOf("preference" to "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(400))
            assertFalse(votes.existsByScheduleCandidateId(candidate.id))
        }

        @Test
        fun `비참여자와 탈퇴한 참여자는 투표할 수 없다`() {
            givenPoll(deadline)
            expectBusinessError(
                perform(put(voteUrl), outsider, mapOf("preference" to "PREFER")),
                MeetingMemberNotFoundException(meeting.id, outsider.id),
            )
            joinedMember.leave()
            expectBusinessError(
                perform(put(voteUrl), member, mapOf("preference" to "PREFER")),
                MeetingMemberAlreadyLeftException(meeting.id, member.id),
            )
        }

        @Test
        fun `마감 상태의 투표소에는 투표할 수 없다`() {
            givenPoll(deadline)
            poll.close()
            expectBusinessError(
                perform(put(voteUrl), member, mapOf("preference" to "PREFER")),
                SchedulePollClosedException(),
            )
            assertFalse(votes.existsByScheduleCandidateId(candidate.id))
        }

        @Test
        fun `OPEN 상태여도 마감 시간이 지나면 투표할 수 없다`() {
            givenPoll(LocalDateTime.now().minusMinutes(1))
            expectBusinessError(
                perform(put(voteUrl), member, mapOf("preference" to "PREFER")),
                SchedulePollClosedException(),
            )
        }

        @Test
        fun `다른 모임의 후보에 투표할 수 없다`() {
            givenPoll(deadline)
            val otherMeeting = meetings.save(Meeting(host, "다른 모임", ""))
            val otherPoll = polls.save(SchedulePoll.create(otherMeeting, deadline))
            val other = addCandidate(otherPoll, candidateDate)
            expectBusinessError(
                perform(
                    put(url + "/candidates/" + other.id + "/vote"),
                    member,
                    mapOf("preference" to "PREFER"),
                ),
                ScheduleCandidateNotFoundException(other.id),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["POST", "GET", "PATCH", "RESULTS", "CANDIDATE_POST", "CANDIDATE_PATCH", "CANDIDATE_DELETE", "VOTE"],
    )
    fun `모든 Schedule API는 인증이 없으면 401을 반환한다`(endpoint: String) {
        val request =
            when (endpoint) {
                "POST" -> post(url)
                "GET" -> get(url)
                "PATCH" -> patch(url)
                "RESULTS" -> get(url + "/results")
                "CANDIDATE_POST" -> post(url + "/candidates")
                "CANDIDATE_PATCH" -> patch(url + "/candidates/1")
                "CANDIDATE_DELETE" -> delete(url + "/candidates/1")
                "VOTE" -> put(url + "/candidates/1/vote")
                else -> throw IllegalArgumentException(endpoint)
            }
        // 인증 실패는 MVC 예외 처리 전에 보안 필터에서 직접 응답한다.
        val result = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
        expectError(result, 401, "인증이 필요합니다.")
        assertNull(result.andReturn().resolvedException)
    }

    private fun createPollFixture(): SchedulePoll = polls.save(SchedulePoll.create(meeting, deadline))

    private fun addCandidate(
        poll: SchedulePoll,
        date: LocalDate,
    ): ScheduleCandidate = candidates.save(poll.addCandidate(date))

    private fun perform(
        request: MockHttpServletRequestBuilder,
        user: User,
        body: Any?,
    ): ResultActions {
        // 요청 전에 픽스처를 DB에 반영해 신규 엔티티의 컬렉션 상태가 API 동작에 섞이지 않게 한다.
        entityManager.flush()
        request.header("Authorization", "Bearer " + tokens.createAccessToken(user.id))
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body))
        }
        return mvc.perform(request)
    }

    private fun expectBusinessError(
        result: ResultActions,
        expected: BusinessException,
    ) {
        result.andExpect { mvcResult ->
            val actual = mvcResult.resolvedException
            assertNotNull(actual, "정의한 비즈니스 예외가 발생해야 한다")
            assertEquals(expected::class.java, checkNotNull(actual).javaClass, "다른 원인의 예외로 통과하면 안 된다")
            assertEquals(expected.message, checkNotNull(actual).message)
        }
        expectError(result, expected.code, expected.message)
    }

    private fun expectValidationError(
        result: ResultActions,
        field: String,
        message: String,
    ) {
        result.andExpect { mvcResult ->
            val exception =
                assertInstanceOf(MethodArgumentNotValidException::class.java, mvcResult.resolvedException)
            val fieldError = exception.bindingResult.getFieldError(field)
            assertNotNull(fieldError, "요청한 필드에서 검증 오류가 발생해야 한다")
            assertEquals(message, checkNotNull(fieldError).defaultMessage)
        }
        expectError(result, 400, message)
    }

    private fun expectError(
        result: ResultActions,
        code: Int,
        message: String?,
    ) {
        result
            .andExpect(status().`is`(code))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.message").value(message))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }
}
