package com.prgms.backend.domain.user.service

import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.enums.MeetingStatus
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.meeting.service.MeetingMemberService
import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.repository.getUser
import com.prgms.backend.global.exception.custom.user.UserWithdrawActivateHostMeetingException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional
class UserWithdrawService(
    private val userRepository: UserRepository,
    private val meetingRepository: MeetingRepository,
    private val meetingMemberService: MeetingMemberService,
    private val meetingMemberRepository: MeetingMemberRepository,
) {
    // 회원 탈퇴
    fun withdraw(userId: Long) {
        val user = userRepository.getUser(userId)

        // host로 참여, 완료되지 않고 삭제되지 않은 모임
        if (meetingRepository.existsByHostIdAndStatusAndDeletedAtIsNull(userId, MeetingStatus.ACTIVE)) {
            throw UserWithdrawActivateHostMeetingException()
        }

        // 멤버로 참여한 모임을 leave
        leaveMeetings(userId)

        // user 탈퇴
        withdrawUser(user)
    }

    // 멤버로 참여한 모임을 leave
    private fun leaveMeetings(userId: Long) {
        // join이고 left_at이 null인 MeetingMember 리스트
        val meetingMembers: List<MeetingMember> =
            meetingMemberRepository.findAllByUserIdAndStatusAndMeetingDeletedAtIsNull(
                userId,
                MeetingMemberStatus.JOINED,
            )

        meetingMembers
            .map { it.meeting }
            .forEach { meeting -> meetingMemberService.leaveMeeting(meeting.id, userId) }
    }

    // user을 탈퇴 처리
    private fun withdrawUser(user: User) {
        user.updateStatus(UserStatus.WITHDRAWN)
        user.updateDeletedAt(LocalDateTime.now())
        user.updateRefreshToken(null)
    }
}
