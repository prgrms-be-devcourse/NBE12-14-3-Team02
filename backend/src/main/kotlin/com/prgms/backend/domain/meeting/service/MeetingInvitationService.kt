package com.prgms.backend.domain.meeting.service

import com.prgms.backend.domain.meeting.dto.response.MeetingInvitationDetailResponse
import com.prgms.backend.domain.meeting.dto.response.MeetingInvitationResponse
import com.prgms.backend.domain.meeting.dto.response.MeetingMemberResponse
import com.prgms.backend.domain.meeting.entity.MeetingInvitation
import com.prgms.backend.domain.meeting.entity.MeetingMember
import com.prgms.backend.domain.meeting.enums.MeetingMemberStatus
import com.prgms.backend.domain.meeting.repository.MeetingInvitationRepository
import com.prgms.backend.domain.meeting.repository.MeetingMemberRepository
import com.prgms.backend.domain.meeting.repository.MeetingRepository
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.domain.user.repository.getUser
import com.prgms.backend.global.exception.custom.meeting.AlreadyMeetingMemberException
import com.prgms.backend.global.exception.custom.meeting.MeetingAccessDeniedException
import com.prgms.backend.global.exception.custom.meeting.MeetingInvitationExpiredException
import com.prgms.backend.global.exception.custom.meeting.MeetingInvitationNotFoundException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotActiveException
import com.prgms.backend.global.exception.custom.meeting.MeetingNotFoundException
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
@Transactional(readOnly = true)
class MeetingInvitationService(
    private val meetingRepository: MeetingRepository,
    private val meetingInvitationRepository: MeetingInvitationRepository,
    private val meetingMemberRepository: MeetingMemberRepository,
    private val userRepository: UserRepository,
) {
    // 초대 저장
    @Transactional
    fun createInvitation(
        meetingId: Long,
        userId: Long,
    ): MeetingInvitationResponse {
        val meeting =
            meetingRepository.findByIdAndDeletedAtIsNullForShare(meetingId)
                ?: throw MeetingNotFoundException(meetingId)

        if (!meeting.isHost(userId)) {
            throw MeetingAccessDeniedException(meetingId, userId)
        }
        if (!meeting.isActive()) {
            throw MeetingNotActiveException(meetingId)
        }

        val inviteCode = UUID.randomUUID().toString()
        val expiresAt = LocalDateTime.now().plusDays(INVITATION_EXPIRE_DAYS)
        val invitation = MeetingInvitation(meeting, inviteCode, expiresAt)
        val savedInvitation = meetingInvitationRepository.save(invitation)
        return MeetingInvitationResponse.from(savedInvitation)
    }

    // 초대를 통한 모임 참여
    @Transactional
    fun joinMeeting(
        inviteCode: String,
        userId: Long,
    ): MeetingMemberResponse {
        // 잠금 전에 모임 엔티티를 로딩하지 않도록 ID만 조회한다.
        val meetingId =
            meetingInvitationRepository.findMeetingIdByInviteCode(inviteCode)
                ?: throw MeetingInvitationNotFoundException(inviteCode)

        val meeting =
            meetingRepository.findByIdAndDeletedAtIsNullForUpdate(meetingId)
                ?: throw MeetingNotFoundException(meetingId)

        // 모임 WRITE LOCK 획득 후 초대를 다시 확인한다.
        val invitation =
            meetingInvitationRepository.findByInviteCode(inviteCode)
                ?: throw MeetingInvitationNotFoundException(inviteCode)
        if (invitation.isExpired()) {
            throw MeetingInvitationExpiredException()
        }
        if (!meeting.isActive()) {
            throw MeetingNotActiveException(meetingId)
        }

        val user = userRepository.getUser(userId, "존재하지 않는 회원입니다.")

        // REPEATABLE READ에서도 잠금 대기 중 커밋된 가입 상태를 확인한다.
        val existingMember = meetingMemberRepository.findByMeetingIdAndUserIdForUpdate(meetingId, userId)
        val meetingMember =
            if (existingMember != null) {
                if (existingMember.isJoined()) {
                    throw AlreadyMeetingMemberException(meetingId, userId)
                }

                // LEFT 기록을 재사용한다.
                existingMember.rejoin()
                existingMember
            } else {
                val newMember = MeetingMember(meeting, user)
                try {
                    // DB 제약 위반을 이 메서드 안에서 확인하기 위해 즉시 반영한다.
                    meetingMemberRepository.saveAndFlush(newMember)
                } catch (e: DataIntegrityViolationException) {
                    if (isDuplicateMembership(e)) {
                        throw AlreadyMeetingMemberException(meetingId, userId)
                    }
                    throw e
                }
                newMember
            }

        return MeetingMemberResponse.from(meetingMember)
    }

    private fun isDuplicateMembership(exception: DataIntegrityViolationException): Boolean {
        var cause: Throwable? = exception
        while (cause != null) {
            if (cause is ConstraintViolationException) {
                val constraintName = cause.constraintName
                // MySQL은 제약 이름 앞에 테이블 이름을 붙여 반환하기도 한다.
                return constraintName == MEMBERSHIP_UNIQUE_CONSTRAINT ||
                    constraintName == "meeting_members.$MEMBERSHIP_UNIQUE_CONSTRAINT"
            }
            cause = cause.cause
        }
        return false
    }

    // 초대 코드 목록 조회(모임장만 가능)
    fun getInvitations(
        meetingId: Long,
        userId: Long,
    ): List<MeetingInvitationResponse> {
        val meeting =
            meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                ?: throw MeetingNotFoundException(meetingId)
        if (!meeting.isHost(userId)) {
            throw MeetingAccessDeniedException(meetingId, userId)
        }

        return meetingInvitationRepository
            .findAllByMeetingId(meetingId)
            .map { MeetingInvitationResponse.from(it) }
    }

    // 초대받은 모임 정보 조회
    fun getInvitation(
        inviteCode: String,
        userId: Long,
    ): MeetingInvitationDetailResponse {
        val invitation = getValidInvitation(inviteCode)
        val meetingId = invitation.meeting.id
        val alreadyJoined =
            meetingMemberRepository.existsByMeetingIdAndUserIdAndStatus(
                meetingId,
                userId,
                MeetingMemberStatus.JOINED,
            )
        val participantCount =
            meetingMemberRepository.countByMeetingIdAndStatus(
                meetingId,
                MeetingMemberStatus.JOINED,
            )
        return MeetingInvitationDetailResponse.from(invitation, alreadyJoined, participantCount)
    }

    private fun getValidInvitation(inviteCode: String): MeetingInvitation {
        val invitation =
            meetingInvitationRepository.findByInviteCode(inviteCode)
                ?: throw MeetingInvitationNotFoundException(inviteCode)
        if (invitation.isExpired()) {
            throw MeetingInvitationExpiredException()
        }

        val meeting = invitation.meeting
        if (meeting.isDeleted()) {
            throw MeetingNotFoundException(meeting.id)
        }
        if (!meeting.isActive()) {
            throw MeetingNotActiveException(meeting.id)
        }

        return invitation
    }

    // 초대 코드 삭제
    @Transactional
    fun deleteInvitation(
        meetingId: Long,
        invitationId: Long,
        userId: Long,
    ) {
        val meeting =
            meetingRepository.findByIdAndDeletedAtIsNullForShare(meetingId)
                ?: throw MeetingNotFoundException(meetingId)
        if (!meeting.isHost(userId)) {
            throw MeetingAccessDeniedException(meetingId, userId)
        }
        if (!meeting.isActive()) {
            throw MeetingNotActiveException(meetingId)
        }

        val invitation =
            meetingInvitationRepository.findByIdOrNull(invitationId)
                ?: throw MeetingInvitationNotFoundException(invitationId)
        if (invitation.meeting.id != meetingId) {
            throw MeetingInvitationNotFoundException(invitationId)
        }

        meetingInvitationRepository.delete(invitation)
    }

    companion object {
        private const val INVITATION_EXPIRE_DAYS = 7L
        private const val MEMBERSHIP_UNIQUE_CONSTRAINT = "uk_meeting_member_meeting_user"
    }
}
