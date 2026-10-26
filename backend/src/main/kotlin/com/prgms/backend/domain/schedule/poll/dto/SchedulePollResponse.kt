package com.prgms.backend.domain.schedule.poll.dto

import com.prgms.backend.domain.schedule.candidate.dto.ScheduleCandidateResponse
import com.prgms.backend.domain.schedule.poll.entity.SchedulePoll
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import java.time.LocalDateTime

class SchedulePollResponse private constructor() {
    data class Created(
        val id: Long,
        val meetingId: Long,
        val deadline: LocalDateTime,
        val status: SchedulePollStatus,
    ) {
        companion object {
            @JvmStatic
            fun from(schedulePoll: SchedulePoll): Created =
                Created(schedulePoll.id, schedulePoll.meeting.id, schedulePoll.deadline, schedulePoll.status)
        }
    }

    data class Detail(
        val id: Long,
        val meetingId: Long,
        val deadline: LocalDateTime,
        val status: SchedulePollStatus,
        val candidates: List<ScheduleCandidateResponse.Summary>,
    ) {
        companion object {
            @JvmStatic
            fun from(schedulePoll: SchedulePoll): Detail =
                Detail(
                    schedulePoll.id,
                    schedulePoll.meeting.id,
                    schedulePoll.deadline,
                    schedulePoll.status,
                    schedulePoll
                        .getCandidates()
                        .map {
                            ScheduleCandidateResponse.Summary.from(it)
                        },
                )
        }
    }

    data class DeadlineUpdate(
        val id: Long,
        val deadline: LocalDateTime,
        val status: SchedulePollStatus,
    ) {
        companion object {
            @JvmStatic
            fun from(schedulePoll: SchedulePoll): DeadlineUpdate =
                DeadlineUpdate(schedulePoll.id, schedulePoll.deadline, schedulePoll.status)
        }
    }
}
