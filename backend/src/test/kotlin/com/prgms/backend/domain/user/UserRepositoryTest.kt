package com.prgms.backend.domain.user

import com.prgms.backend.domain.user.entity.User
import com.prgms.backend.domain.user.enums.UserStatus
import com.prgms.backend.domain.user.repository.UserRepository
import com.prgms.backend.support.MySqlContainerSupport
import jakarta.persistence.EntityManager
import org.hibernate.Hibernate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import java.time.LocalDateTime
import kotlin.test.assertNotNull

// 엔티티 매핑·기본값·파생 쿼리·제약 조건·프록시를 실제 MySQL로 확인
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
internal class UserRepositoryTest : MySqlContainerSupport() {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `저장한 회원을 다시 조회하면 기본 상태와 생성·수정 시각이 채워져 있다`() {
        // given
        val user = userRepository.save(User("user@test.com", "회원", "encoded-password"))
        flushAndClear()

        // when
        val found = findUser(user.id)

        // then
        assertNotEquals(0L, user.id)
        assertEquals("user@test.com", found.email)
        assertEquals("회원", found.nickname)
        assertEquals("encoded-password", found.password)
        assertEquals(UserStatus.ACTIVE, found.status)
        assertNull(found.deletedAt)
        assertNull(found.refreshToken)
        assertNotNull(found.createdAt)
        assertNotNull(found.updatedAt)
    }

    @Test
    fun `회원 상태는 enum 이름 문자열로 저장한다`() {
        // given
        val user = userRepository.save(User("user@test.com", "회원", "encoded-password"))
        flushAndClear()

        // when
        val status =
            entityManager
                .createNativeQuery("select status from users where id = :id")
                .setParameter("id", user.id)
                .singleResult

        // then
        assertEquals("ACTIVE", status)
    }

    @Test
    fun `탈퇴 처리한 상태 변경이 DB에 반영된다`() {
        // given
        val user = userRepository.save(User("user@test.com", "회원", "encoded-password"))
        user.updateRefreshToken("refresh-token")
        flushAndClear()

        // when
        val managed = findUser(user.id)
        managed.updateStatus(UserStatus.WITHDRAWN)
        managed.updateDeletedAt(LocalDateTime.now())
        managed.updateRefreshToken(null)
        flushAndClear()

        // then
        val found = findUser(user.id)
        assertEquals(UserStatus.WITHDRAWN, found.status)
        assertNotNull(found.deletedAt)
        assertNull(found.refreshToken)
    }

    @Test
    fun `이메일과 닉네임 존재 여부를 확인할 수 있다`() {
        // given
        userRepository.save(User("user@test.com", "회원", "encoded-password"))
        flushAndClear()

        // when & then
        assertTrue(userRepository.existsByEmail("user@test.com"))
        assertFalse(userRepository.existsByEmail("none@test.com"))
        assertTrue(userRepository.existsByNickname("회원"))
        assertFalse(userRepository.existsByNickname("없는회원"))
    }

    @Test
    fun `이메일로 회원을 조회하고 없으면 빈 값을 반환한다`() {
        // given
        val user = userRepository.save(User("user@test.com", "회원", "encoded-password"))
        flushAndClear()

        // when
        val found = userRepository.findByEmail("user@test.com")
        val notFound = userRepository.findByEmail("none@test.com")

        // then
        assertEquals(user.id, found?.id)
        assertNull(notFound)
    }

    @Test
    fun `활성 상태이고 삭제 시각이 없는 회원만 활성 회원으로 판단한다`() {
        // given
        val active = userRepository.save(User("active@test.com", "활성", "encoded-password"))

        val withdrawn = userRepository.save(User("withdrawn@test.com", "탈퇴", "encoded-password"))
        withdrawn.updateStatus(UserStatus.WITHDRAWN)
        withdrawn.updateDeletedAt(LocalDateTime.now())

        // 상태는 ACTIVE지만 삭제 시각만 남은 비정상 데이터도 차단해야 한다
        val deletedOnly = userRepository.save(User("deleted@test.com", "삭제", "encoded-password"))
        deletedOnly.updateDeletedAt(LocalDateTime.now())
        flushAndClear()

        // when & then
        assertTrue(userRepository.existsByIdAndStatusAndDeletedAtIsNull(active.id, UserStatus.ACTIVE))
        assertFalse(userRepository.existsByIdAndStatusAndDeletedAtIsNull(withdrawn.id, UserStatus.ACTIVE))
        assertFalse(userRepository.existsByIdAndStatusAndDeletedAtIsNull(deletedOnly.id, UserStatus.ACTIVE))
        assertFalse(userRepository.existsByIdAndStatusAndDeletedAtIsNull(-1L, UserStatus.ACTIVE))
    }

    @Test
    fun `이미 있는 이메일로는 저장할 수 없다`() {
        // given
        userRepository.saveAndFlush(User("user@test.com", "회원", "encoded-password"))

        // when & then
        assertThrows<DataIntegrityViolationException> {
            userRepository.saveAndFlush(User("user@test.com", "다른회원", "encoded-password"))
        }
    }

    @Test
    fun `이미 있는 닉네임으로는 저장할 수 없다`() {
        // given
        userRepository.saveAndFlush(User("user@test.com", "회원", "encoded-password"))

        // when & then
        assertThrows<DataIntegrityViolationException> {
            userRepository.saveAndFlush(User("other@test.com", "회원", "encoded-password"))
        }
    }

    @Test
    fun `회원 지연 로딩 프록시를 만들 수 있고 프록시로 필드를 읽을 수 있다`() {
        // given
        // Meeting.host 같은 LAZY 연관관계는 User 프록시에 의존한다.
        // 코틀린에서 클래스나 getter가 final이면 프록시가 만들어지지 않거나 null을 읽게 된다.
        val user = userRepository.save(User("user@test.com", "회원", "encoded-password"))
        flushAndClear()

        // when
        val reference = userRepository.getReferenceById(user.id)

        // then
        assertFalse(Hibernate.isInitialized(reference))
        assertEquals(user.id, reference.id)
        assertEquals("user@test.com", reference.email)
        assertTrue(Hibernate.isInitialized(reference))
    }

    private fun findUser(userId: Long): User = assertNotNull(userRepository.findByIdOrNull(userId))

    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }
}
