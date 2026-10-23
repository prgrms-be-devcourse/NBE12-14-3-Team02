package com.prgms.backend.domain.meeting.repository

import com.prgms.backend.domain.meeting.entity.Meeting
import com.prgms.backend.domain.meeting.enums.MeetingStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MeetingRepository : JpaRepository<Meeting, Long> {
    fun existsByHostIdAndStatusAndDeletedAtIsNull(
        hostId: Long,
        status: MeetingStatus,
    ): Boolean

    fun findByHostIdAndDeletedAtIsNull(hostId: Long): List<Meeting>

    fun findByIdAndDeletedAtIsNull(meetingId: Long): Meeting?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
    SELECT m
    FROM Meeting m
    WHERE m.id = :meetingId
      AND m.deletedAt IS NULL
    """,
    )
    fun findByIdAndDeletedAtIsNullForUpdate(
        @Param("meetingId") meetingId: Long,
    ): Meeting?

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query(
        """
    SELECT m
    FROM Meeting m
    WHERE m.id = :meetingId
      AND m.deletedAt IS NULL
    """,
    )
    fun findByIdAndDeletedAtIsNullForShare(
        @Param("meetingId") meetingId: Long,
    ): Meeting?
}
