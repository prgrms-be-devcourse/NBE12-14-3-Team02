package com.prgms.backend.domain.content

import com.prgms.backend.domain.content.enums.ContentPollStatus
import com.prgms.backend.domain.content.repository.ContentPollRepository
import com.prgms.backend.domain.content.service.ContentPollCloseScheduler
import com.prgms.backend.domain.content.service.ContentPollCloseService
import com.prgms.backend.domain.meeting.enums.MeetingStatus
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ContentPollCloseSchedulerTest {
    private val repository: ContentPollRepository = mock()
    private val closeService: ContentPollCloseService = mock()
    private val scheduler = ContentPollCloseScheduler(repository, closeService)

    @Test
    fun `한 투표 마감이 실패해도 나머지 투표는 계속 마감`() {
        // given
        whenever(repository.findExpiredIds(eq(ContentPollStatus.OPEN), eq(MeetingStatus.ACTIVE), any()))
            .thenReturn(listOf(1L, 2L, 3L))
        doThrow(IllegalStateException("fail")).whenever(closeService).closeOneIfExpired(2L)

        // when
        assertDoesNotThrow(scheduler::closeExpired)

        // then
        verify(closeService).closeOneIfExpired(1L)
        verify(closeService).closeOneIfExpired(2L)
        verify(closeService).closeOneIfExpired(3L)
    }
}
