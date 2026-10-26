package com.prgms.backend.domain.expense;

import com.prgms.backend.domain.expense.service.ReceiptStorage;
import com.prgms.backend.global.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

// 실제 임시 폴더에 영수증 파일을 저장·조회·삭제
class ReceiptStorageTest {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    @TempDir Path root;
    private ReceiptStorage storage;

    @BeforeEach void setup() {
        storage = new ReceiptStorage(root.toString());
    }

    @ParameterizedTest @ValueSource(strings = {"png", "jpg"})
    @DisplayName("JPG·PNG 영수증을 PNG로 다시 저장하고 발급한 키로 읽는다")
    void storeAndRead(String format) throws IOException {
        String key = storage.store(file(image(20, 10, BufferedImage.TYPE_INT_RGB), format));

        assertTrue(key.matches("[a-f0-9-]{36}\\.png"));
        assertTrue(Files.exists(root.resolve(key)));
        byte[] saved = storage.read(key);
        assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(saved, PNG_SIGNATURE.length));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(saved));
        assertEquals(20, image.getWidth());
        assertEquals(10, image.getHeight());
    }

    @Test @DisplayName("파일 누락·빈 파일·5MB 초과·이미지가 아닌 파일·GIF는 400으로 거절하고 파일을 남기지 않는다")
    void rejectInvalidFiles() throws IOException {
        assertCode(400, () -> storage.store(null));
        assertCode(400, () -> storage.store(new MockMultipartFile("file", new byte[0])));
        assertCode(400, () -> storage.store(new MockMultipartFile("file", new byte[5 * 1024 * 1024 + 1])));
        assertCode(400, () -> storage.store(new MockMultipartFile("file", "not an image".getBytes())));
        var gif = file(image(5, 5, BufferedImage.TYPE_BYTE_INDEXED), "gif");
        assertCode(400, () -> storage.store(gif));
        assertNoStoredFiles();
    }

    @Test @DisplayName("2,000만 화소를 넘는 이미지는 디코딩하지 않고 거절")
    void rejectTooManyPixels() throws IOException {
        var large = file(image(5000, 4001, BufferedImage.TYPE_BYTE_BINARY), "png");
        var e = assertCode(400, () -> storage.store(large));
        assertTrue(e.getMessage().contains("2,000만 화소"));
        assertNoStoredFiles();
    }

    @Test @DisplayName("키 누락·형식이 다른 키·저장소 밖 경로·없는 파일은 404")
    void readRejectsUnknownKeys() {
        assertCode(404, () -> storage.read(null));
        assertCode(404, () -> storage.read("receipt/original"));
        assertCode(404, () -> storage.read("../" + UUID.randomUUID() + ".png"));
        assertCode(404, () -> storage.read(UUID.randomUUID() + ".png"));
    }

    @Test @DisplayName("저장소 형식의 키만 삭제하고, 과거 형식이나 저장소 밖 경로는 무시")
    void deleteOnlyStoredKeys() throws IOException {
        String key = store();
        Path legacy = Files.writeString(root.resolve("legacy.png"), "keep");

        storage.delete(null);
        storage.delete("legacy.png");
        storage.delete("../" + key);
        assertTrue(Files.exists(root.resolve(key)));
        assertTrue(Files.exists(legacy));

        storage.delete(key);
        assertFalse(Files.exists(root.resolve(key)));
        assertDoesNotThrow(() -> storage.delete(key)); // 이미 지워진 파일
    }

    @Test @DisplayName("커밋 후에만 파일을 삭제하고, 롤백되면 파일을 유지")
    void deleteAfterCommit() throws IOException {
        String committed = store();
        String rolledBack = store();

        TransactionSynchronizationManager.initSynchronization();
        try {
            storage.deleteAfterCommit(committed);
            assertTrue(Files.exists(root.resolve(committed)));
            TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations());
            assertFalse(Files.exists(root.resolve(committed)));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager.initSynchronization();
        try {
            storage.deleteAfterCommit(rolledBack);
            TransactionSynchronizationUtils.invokeAfterCompletion(
                    TransactionSynchronizationManager.getSynchronizations(), TransactionSynchronization.STATUS_ROLLED_BACK);
            assertTrue(Files.exists(root.resolve(rolledBack)));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test @DisplayName("영수증이 없던 지출은 커밋 후 삭제를 등록하지 않는다")
    void deleteAfterCommitWithoutKey() {
        // 트랜잭션 동기화가 없을 때 등록을 시도하면 예외가 발생합니다.
        assertDoesNotThrow(() -> storage.deleteAfterCommit(null));
    }

    private String store() throws IOException {
        return storage.store(file(image(5, 5, BufferedImage.TYPE_INT_RGB), "png"));
    }

    private void assertNoStoredFiles() throws IOException {
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }

    private BufferedImage image(int width, int height, int type) {
        return new BufferedImage(width, height, type);
    }

    private MockMultipartFile file(BufferedImage image, String format) throws IOException {
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes), format + " writer");
        return new MockMultipartFile("file", "receipt." + format, null, bytes.toByteArray());
    }

    private BusinessException assertCode(int code, Executable action) {
        var e = assertThrows(BusinessException.class, action);
        assertEquals(code, e.getCode());
        return e;
    }
}
