package com.prgms.backend.domain.schedule.scheduler

import com.prgms.backend.domain.meeting.enums.MeetingStatus
import com.prgms.backend.domain.schedule.poll.entity.SchedulePollStatus
import com.prgms.backend.domain.schedule.poll.repository.SchedulePollRepository
import com.prgms.backend.domain.schedule.poll.service.SchedulePollCloseService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SchedulePollCloseSchedulerTest {
    @Test
    fun `한 투표의 마감이 실패해도 다음 투표를 계속 처리한다`() {
        val polls = mock<SchedulePollRepository>()
        val closer = mock<SchedulePollCloseService>()
        whenever(polls.findExpiredIds(eq(SchedulePollStatus.OPEN), eq(MeetingStatus.ACTIVE), any()))
            .thenReturn(listOf(1L, 2L))
        doThrow(IllegalStateException("close failure")).whenever(closer).closeOneIfExpired(1L)
        val scheduler = SchedulePollCloseScheduler(polls, closer)

        assertDoesNotThrow { scheduler.closeExpired() }

        verify(closer).closeOneIfExpired(2L)
    }
}
