package com.prgms.backend.domain.schedule.poll.entity

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.global.exception.custom.schedule.ScheduleCandidateLimitExceededException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime

class SchedulePollTest {
    private lateinit var poll: SchedulePoll

    @BeforeEach
    fun setUp() {
        val host = User("host@example.com", "모임장", "password")
        val meeting = Meeting(host, "테스트 모임", "일정 조율 테스트")
        poll = SchedulePoll.create(meeting, LocalDateTime.of(2030, 1, 31, 18, 0))
    }

    @Test
    fun `일정 후보는 10개까지 등록할 수 있다`() {
        val firstDate = LocalDate.of(2030, 2, 1)

        for (i in 0 until 10) {
            poll.addCandidate(firstDate.plusDays(i.toLong()))
        }

        assertEquals(10, poll.getCandidates().size)
        assertEquals(firstDate, poll.getCandidates().first().candidateDate)
        assertEquals(firstDate.plusDays(9), poll.getCandidates().last().candidateDate)
    }

    @Test
    fun `11번째 일정 후보는 거부하고 기존 10개를 유지한다`() {
        val firstDate = LocalDate.of(2030, 2, 1)

        for (i in 0 until 10) {
            poll.addCandidate(firstDate.plusDays(i.toLong()))
        }

        assertThrows<ScheduleCandidateLimitExceededException> { poll.addCandidate(firstDate.plusDays(10)) }
        assertEquals(10, poll.getCandidates().size)
    }
}
