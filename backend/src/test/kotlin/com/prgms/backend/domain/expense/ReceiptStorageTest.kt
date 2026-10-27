package com.prgms.backend.domain.expense

import com.prgms.backend.domain.expense.service.ReceiptStorage
import com.prgms.backend.global.exception.BusinessException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertNotNull

// 실제 임시 폴더에 영수증 파일을 저장·조회·삭제
internal class ReceiptStorageTest {
    @TempDir
    lateinit var root: Path
    private lateinit var storage: ReceiptStorage

    @BeforeEach
    fun setup() {
        storage = ReceiptStorage(root.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["png", "jpg"])
    fun `JPG·PNG 영수증을 PNG로 다시 저장하고 발급한 키로 읽는다`(format: String) {
        val key = storage.store(file(image(20, 10, BufferedImage.TYPE_INT_RGB), format))

        assertTrue(key.matches("[a-f0-9-]{36}\\.png".toRegex()))
        assertTrue(Files.exists(root.resolve(key)))
        val saved = storage.read(key)
        assertArrayEquals(PNG_SIGNATURE, saved.copyOf(PNG_SIGNATURE.size))
        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(saved)))
        assertEquals(20, image.width)
        assertEquals(10, image.height)
    }

    @Test
    fun `파일 누락·빈 파일·5MB 초과·이미지가 아닌 파일·GIF는 400으로 거절하고 파일을 남기지 않는다`() {
        assertCode(400) { storage.store(null) }
        assertCode(400) { storage.store(MockMultipartFile("file", ByteArray(0))) }
        assertCode(400) {
            storage.store(
                MockMultipartFile(
                    "file",
                    ByteArray(5 * 1024 * 1024 + 1),
                ),
            )
        }
        assertCode(400) { storage.store(MockMultipartFile("file", "not an image".toByteArray())) }
        val gif = file(image(5, 5, BufferedImage.TYPE_BYTE_INDEXED), "gif")
        assertCode(400) { storage.store(gif) }
        assertNoStoredFiles()
    }

    @Test
    fun `2,000만 화소를 넘는 이미지는 디코딩하지 않고 거절`() {
        val large = file(image(5000, 4001, BufferedImage.TYPE_BYTE_BINARY), "png")
        val e = assertCode(400) { storage.store(large) }
        assertTrue(assertNotNull(e.message).contains("2,000만 화소"))
        assertNoStoredFiles()
    }

    @Test
    fun `키 누락·형식이 다른 키·저장소 밖 경로·없는 파일은 404`() {
        assertCode(404) { storage.read(null) }
        assertCode(404) { storage.read("receipt/original") }
        assertCode(404) { storage.read("../" + UUID.randomUUID() + ".png") }
        assertCode(404) { storage.read(UUID.randomUUID().toString() + ".png") }
    }

    @Test
    fun `저장소 형식의 키만 삭제하고, 과거 형식이나 저장소 밖 경로는 무시`() {
        val key = store()
        val legacy = Files.writeString(root.resolve("legacy.png"), "keep")

        storage.delete(null)
        storage.delete("legacy.png")
        storage.delete("../" + key)
        assertTrue(Files.exists(root.resolve(key)))
        assertTrue(Files.exists(legacy))

        storage.delete(key)
        assertFalse(Files.exists(root.resolve(key)))
        assertDoesNotThrow { storage.delete(key) } // 이미 지워진 파일
    }

    @Test
    fun `커밋 후에만 파일을 삭제하고, 롤백되면 파일을 유지`() {
        val committed = store()
        val rolledBack = store()

        TransactionSynchronizationManager.initSynchronization()
        try {
            storage.deleteAfterCommit(committed)
            assertTrue(Files.exists(root.resolve(committed)))
            TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations())
            assertFalse(Files.exists(root.resolve(committed)))
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }

        TransactionSynchronizationManager.initSynchronization()
        try {
            storage.deleteAfterCommit(rolledBack)
            TransactionSynchronizationUtils.invokeAfterCompletion(
                TransactionSynchronizationManager.getSynchronizations(),
                TransactionSynchronization.STATUS_ROLLED_BACK,
            )
            assertTrue(Files.exists(root.resolve(rolledBack)))
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `영수증이 없던 지출은 커밋 후 삭제를 등록하지 않는다`() {
        // 트랜잭션 동기화가 없을 때 등록을 시도하면 예외가 발생합니다.
        assertDoesNotThrow { storage.deleteAfterCommit(null) }
    }

    private fun store(): String = storage.store(file(image(5, 5, BufferedImage.TYPE_INT_RGB), "png"))

    private fun assertNoStoredFiles() {
        Files.list(root).use { files ->
            assertEquals(0L, files.count())
        }
    }

    private fun image(
        width: Int,
        height: Int,
        type: Int,
    ): BufferedImage = BufferedImage(width, height, type)

    private fun file(
        image: BufferedImage,
        format: String,
    ): MockMultipartFile {
        val bytes = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, format, bytes), format + " writer")
        return MockMultipartFile("file", "receipt." + format, null, bytes.toByteArray())
    }

    private fun assertCode(
        code: Int,
        action: () -> Unit,
    ): BusinessException {
        val e = assertThrows<BusinessException>(action)
        assertEquals(code, e.code)
        return e
    }

    companion object {
        private val PNG_SIGNATURE =
            byteArrayOf(
                0x89.toByte(),
                'P'.code.toByte(),
                'N'.code.toByte(),
                'G'.code.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                0x1a,
                '\n'.code.toByte(),
            )
    }
}
