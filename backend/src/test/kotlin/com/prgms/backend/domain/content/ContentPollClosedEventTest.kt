package com.prgms.backend.domain.content

import com.prgms.backend.domain.content.integration.ContentNotificationWriter
import com.prgms.backend.domain.content.integration.ContentPollClosedEvent
import com.prgms.backend.domain.content.integration.ContentPollClosedEventListener
import com.prgms.backend.domain.notification.service.NotificationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.util.function.Supplier
import javax.sql.DataSource

class ContentPollClosedEventTest {
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var notificationService: NotificationService
    private lateinit var pollConnection: Connection
    private lateinit var notificationConnection: Connection
    private lateinit var transaction: TransactionTemplate

    private val event = ContentPollClosedEvent(10L, 100L)

    @Configuration
    @EnableTransactionManagement
    class TransactionConfig

    @BeforeEach
    fun setUp() {
        notificationService = mock()
        pollConnection = mock()
        notificationConnection = mock()
        whenever(pollConnection.autoCommit).thenReturn(true)
        whenever(notificationConnection.autoCommit).thenReturn(true)

        // 첫 연결은 마감 트랜잭션, 두 번째 연결은 REQUIRES_NEW 알림 트랜잭션
        val dataSource: DataSource = mock()
        whenever(dataSource.connection).thenReturn(pollConnection, notificationConnection)
        val transactionManager = DataSourceTransactionManager(dataSource)

        context = AnnotationConfigApplicationContext()
        context.registerBean(NotificationService::class.java, Supplier { notificationService })
        context.registerBean(DataSourceTransactionManager::class.java, Supplier { transactionManager })
        context.register(
            TransactionConfig::class.java,
            ContentPollClosedEventListener::class.java,
            ContentNotificationWriter::class.java,
        )
        context.refresh()

        transaction = TransactionTemplate(transactionManager)
    }

    @AfterEach
    fun tearDown() {
        context.close()
    }

    @Test
    fun `콘텐츠 투표 마감이 커밋된 뒤에 알림을 별도 트랜잭션으로 저장`() {
        // when
        transaction.executeWithoutResult {
            context.publishEvent(event)
            verifyNoInteractions(notificationService)
        }

        // then
        val order = inOrder(pollConnection, notificationService, notificationConnection)
        order.verify(pollConnection).commit()
        order.verify(notificationService).notifyContentClosed(10L)
        order.verify(notificationConnection).commit()
    }

    @Test
    fun `알림 저장이 실패해도 이미 커밋된 콘텐츠 투표 마감은 롤백않음`() {
        // given
        doThrow(IllegalStateException("notification unavailable"))
            .whenever(notificationService)
            .notifyContentClosed(10L)

        // when
        assertDoesNotThrow {
            transaction.executeWithoutResult { context.publishEvent(event) }
        }

        // then
        verify(pollConnection).commit()
        verify(pollConnection, never()).rollback()
        verify(notificationConnection).rollback()
        verify(notificationConnection, never()).commit()
    }

    @Test
    fun `콘텐츠 투표 마감이 롤백되면 알림을 저장하지 않음`() {
        // when
        transaction.executeWithoutResult { status ->
            context.publishEvent(event)
            status.setRollbackOnly()
        }

        // then
        verify(pollConnection).rollback()
        verifyNoInteractions(notificationService, notificationConnection)
    }
}
