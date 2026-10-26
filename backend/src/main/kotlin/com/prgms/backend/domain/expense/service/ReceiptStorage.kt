package com.prgms.backend.domain.expense.service

import com.prgms.backend.global.exception.custom.expense.ExpenseRequestException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.IIOException
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

@Component
class ReceiptStorage(
    @Value("\${app.receipts.directory:./uploads/receipts}") directory: String,
) {
    private val root: Path = Path.of(directory).toAbsolutePath().normalize()

    // 이미지 디코딩·파일 저장은 모임의 DB 잠금을 잡기 전에 처리합니다.
    fun store(file: MultipartFile?): String {
        if (file == null || file.isEmpty || file.size > MAX_FILE_SIZE) {
            throw ExpenseRequestException(400, "영수증은 5MB 이하의 JPG 또는 PNG 1장이어야 합니다.")
        }
        try {
            return file.inputStream.use { source ->
                // 기존 try-with-resources처럼 null 스트림은 닫지 않고 ImageIO의 검사에 맡깁니다.
                val input: ImageInputStream? = ImageIO.createImageInputStream(source)
                input.use { storeAsPng(it) }
            }
        } catch (e: IIOException) {
            throw invalidImage()
        } catch (e: IOException) {
            throw ExpenseRequestException(500, "영수증 파일을 저장하지 못했습니다.")
        }
    }

    private fun storeAsPng(input: ImageInputStream?): String {
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) throw invalidImage()
        val reader = readers.next()
        try {
            val format: String = reader.formatName
            if (!format.equals("JPEG", ignoreCase = true) && !format.equals("PNG", ignoreCase = true)) {
                throw invalidImage()
            }
            reader.input = input
            val pixels = reader.getWidth(0).toLong() * reader.getHeight(0)
            if (pixels > MAX_PIXELS) throw ExpenseRequestException(400, "영수증은 2,000만 화소 이하로 첨부해주세요.")
            val image = reader.read(0)
            val key = "${UUID.randomUUID()}.png"
            Files.createDirectories(root)
            val target = resolve(key)
            try {
                if (!ImageIO.write(image, "png", target.toFile())) throw IOException("Image writer unavailable")
            } catch (e: IOException) {
                Files.deleteIfExists(target)
                throw e
            }
            return key
        } finally {
            reader.dispose()
        }
    }

    fun read(key: String?): ByteArray =
        try {
            Files.readAllBytes(resolve(key))
        } catch (e: IOException) {
            throw ExpenseRequestException(404, "영수증 파일을 찾을 수 없습니다.")
        }

    fun delete(key: String?) {
        if (key == null) return
        // 과거 형식의 키나 저장소 외부 경로는 삭제하지 않습니다.
        if (!KEY_PATTERN.matches(key)) return
        try {
            Files.deleteIfExists(resolve(key))
        } catch (e: IOException) {
            log.warn("영수증 파일 정리가 지연되었습니다.")
        }
    }

    fun deleteAfterCommit(key: String?) {
        if (key == null) return
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = delete(key)
            },
        )
    }

    private fun resolve(key: String?): Path {
        if (key == null || !KEY_PATTERN.matches(key)) {
            throw ExpenseRequestException(404, "영수증 파일을 찾을 수 없습니다.")
        }
        return root.resolve(key)
    }

    private fun invalidImage() = ExpenseRequestException(400, "정상적인 JPG 또는 PNG 이미지를 첨부해주세요.")

    companion object {
        private val log = LoggerFactory.getLogger(ReceiptStorage::class.java)
        private val KEY_PATTERN = Regex("""[a-f0-9-]{36}\.png""")
        private const val MAX_FILE_SIZE = 5 * 1024 * 1024
        private const val MAX_PIXELS = 20_000_000
    }
}
