package com.prgms.backend.domain.schedule.support

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.schedule.candidate.entity.ScheduleCandidate
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.vote.entity.SchedulePreference
import com.prgms.backend.domain.schedule.vote.entity.ScheduleVote
import com.prgms.backend.domain.user.entity.User
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDate
import java.time.LocalDateTime

object ScheduleTestFixtures {
    fun user(
        id: Long,
        nickname: String,
    ): User =
        User("$nickname@example.com", nickname, "password").apply {
            ReflectionTestUtils.setField(this, "id", id)
        }

    fun meeting(
        id: Long,
        host: User,
    ): Meeting =
        Meeting(host, "테스트 모임", "일정 조율").apply {
            ReflectionTestUtils.setField(this, "id", id)
        }

    fun member(
        id: Long,
        meeting: Meeting,
        user: User,
    ): MeetingMember =
        MeetingMember(meeting, user).apply {
            ReflectionTestUtils.setField(this, "id", id)
        }

    fun poll(
        id: Long,
        meeting: Meeting,
        deadline: LocalDateTime,
    ): SchedulePoll =
        SchedulePoll.create(meeting, deadline).apply {
            ReflectionTestUtils.setField(this, "id", id)
        }

    fun candidate(
        id: Long,
        poll: SchedulePoll,
        date: LocalDate,
    ): ScheduleCandidate =
        poll.addCandidate(date).apply {
            ReflectionTestUtils.setField(this, "id", id)
        }

    fun vote(
        candidate: ScheduleCandidate,
        member: MeetingMember,
        preference: SchedulePreference,
    ): ScheduleVote = ScheduleVote.create(candidate, member, preference)
}
