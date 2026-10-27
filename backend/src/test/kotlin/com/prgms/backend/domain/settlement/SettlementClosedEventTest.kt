package com.prgms.backend.domain.settlement

import com.prgms.backend.domain.notification.service.NotificationService
import com.prgms.backend.domain.settlement.integration.SettlementClosedEvent
import com.prgms.backend.domain.settlement.integration.SettlementClosedEventListener
import com.prgms.backend.domain.settlement.integration.SettlementNotificationWriter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.function.Supplier
import javax.sql.DataSource

internal class SettlementClosedEventTest {
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var notifications: NotificationService
    private lateinit var settlementConnection: Connection
    private lateinit var notificationConnection: Connection
    private lateinit var transaction: TransactionTemplate
    private val event = SettlementClosedEvent(10, 100, LocalDateTime.now())

    @Configuration
    @EnableTransactionManagement
    internal class TransactionConfig

    @BeforeEach
    fun setup() {
        notifications = mock<NotificationService>()
        settlementConnection = mock<Connection>()
        notificationConnection = mock<Connection>()
        whenever(settlementConnection.autoCommit).thenReturn(true)
        whenever(notificationConnection.autoCommit).thenReturn(true)
        val dataSource = mock<DataSource>()
        whenever(dataSource.connection)
            .thenReturn(settlementConnection, notificationConnection)
        val manager = DataSourceTransactionManager(dataSource)
        context = AnnotationConfigApplicationContext()
        context.registerBean(
            NotificationService::class.java,
            Supplier { notifications },
        )
        context.registerBean(
            DataSourceTransactionManager::class.java,
            Supplier { manager },
        )
        context.register(
            TransactionConfig::class.java,
            SettlementClosedEventListener::class.java,
            SettlementNotificationWriter::class.java,
        )
        context.refresh()
        transaction = TransactionTemplate(manager)
    }

    @AfterEach
    fun close() {
        context.close()
    }

    @Test
    fun `정산 커밋 후 알림을 별도 트랜잭션으로 커밋한다`() {
        transaction.executeWithoutResult { _ ->
            context.publishEvent(event)
            verifyNoInteractions(notifications)
        }
        val order = inOrder(settlementConnection, notifications, notificationConnection)
        order.verify(settlementConnection).commit()
        order.verify(notifications).notifySettlementClosed(10L)
        order.verify(notificationConnection).commit()
    }

    @Test
    fun `알림 처리 실패는 이미 커밋한 정산에 영향을 주지 않는다`() {
        doThrow(IllegalStateException("notification unavailable"))
            .whenever(notifications)
            .notifySettlementClosed(10L)
        assertDoesNotThrow {
            transaction.executeWithoutResult { _ ->
                context.publishEvent(
                    event,
                )
            }
        }
        verify(settlementConnection).commit()
        verify(settlementConnection, never()).rollback()
        verify(notificationConnection).rollback()
        verify(notificationConnection, never()).commit()
    }

    @Test
    fun `알림 커밋 실패는 이미 커밋한 정산에 영향을 주지 않는다`() {
        doThrow(SQLException("notification commit failed"))
            .whenever(notificationConnection)
            .commit()
        assertDoesNotThrow {
            transaction.executeWithoutResult { _ ->
                context.publishEvent(
                    event,
                )
            }
        }
        verify(settlementConnection).commit()
        verify(settlementConnection, never()).rollback()
        verify(notifications).notifySettlementClosed(10L)
    }

    @Test
    fun `정산을 롤백하면 알림을 보내지 않는다`() {
        transaction.executeWithoutResult { status ->
            context.publishEvent(event)
            status.setRollbackOnly()
        }
        verify(settlementConnection).rollback()
        verifyNoInteractions(notifications, notificationConnection)
    }

    @Test
    fun `트랜잭션 없이 발행한 이벤트는 알림을 보내지 않는다`() {
        context.publishEvent(event)
        verifyNoInteractions(notifications, notificationConnection)
    }
}
