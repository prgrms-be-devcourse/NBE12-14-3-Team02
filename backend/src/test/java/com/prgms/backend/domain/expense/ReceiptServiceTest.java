package com.prgms.backend.domain.expense;

import com.prgms.backend.domain.expense.entity.Expense;
import com.prgms.backend.domain.expense.repository.ExpenseRepository;
import com.prgms.backend.domain.expense.service.ReceiptService;
import com.prgms.backend.domain.expense.service.ReceiptStorage;
import com.prgms.backend.domain.settlement.entity.Settlement;
import com.prgms.backend.domain.settlement.integration.MeetingAccessPort;
import com.prgms.backend.domain.settlement.repository.SettlementRepository;
import com.prgms.backend.global.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.springframework.test.util.ReflectionTestUtils;
import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReceiptServiceTest {
    private static final String OLD_KEY = "old.png";
    private static final String NEW_KEY = "new.png";

    private final ExpenseRepository expenses = mock(ExpenseRepository.class);
    private final MeetingAccessPort access = mock(MeetingAccessPort.class);
    private final SettlementRepository settlements = mock(SettlementRepository.class);
    private final ReceiptStorage storage = mock(ReceiptStorage.class);
    private final Principal principal = () -> "user";
    private final ReceiptService service = new ReceiptService(expenses, access, settlements, storage);

    @BeforeEach void setup() {
        var context = new MeetingAccessPort.Context(1, false, true, Set.of(1L, 2L));
        when(access.requireMember(10, principal)).thenReturn(context);
        when(access.requireMemberForRead(10, principal)).thenReturn(context);
        when(settlements.findByMeetingId(10)).thenReturn(null);
    }

    @Test @DisplayName("본인 지출의 영수증을 교체하면 기존 파일은 커밋 후 삭제를 요청")
    void replace() {
        Expense expense = existing(10, 1);
        expense.attachReceipt(OLD_KEY);

        service.replace(10, 7, principal, NEW_KEY);

        assertEquals(NEW_KEY, expense.getReceiptKey());
        verify(storage).deleteAfterCommit(OLD_KEY);
        verify(storage, never()).delete(any());
        verify(expenses, never()).save(any()); // JPA 변경 감지 사용
    }

    @Test @DisplayName("영수증 삭제는 키를 비우고 기존 파일만 커밋 후 삭제")
    void detach() {
        Expense expense = existing(10, 1);
        expense.attachReceipt(OLD_KEY);

        service.replace(10, 7, principal, null);

        assertNull(expense.getReceiptKey());
        verify(storage).deleteAfterCommit(OLD_KEY);
    }

    @Test @DisplayName("업로드 전 확인은 읽기 권한으로 검사하고 지출을 바꾸지 않는다")
    void checkUpload() {
        Expense expense = existing(10, 1);
        expense.attachReceipt(OLD_KEY);

        service.checkUpload(10, 7, principal);

        assertEquals(OLD_KEY, expense.getReceiptKey());
        verify(access).requireMemberForRead(10, principal);
        verify(access, never()).requireMember(anyLong(), any());
        verifyNoInteractions(storage);
    }

    @Test @DisplayName("다른 사람 지출은 업로드 확인·교체 모두 403")
    void otherOwner() {
        Expense expense = existing(10, 2);
        expense.attachReceipt(OLD_KEY);
        assertAllChangesRejected(403);
        assertEquals(OLD_KEY, expense.getReceiptKey());
    }

    @Test @DisplayName("다른 모임의 지출이나 없는 지출은 404")
    void foreignOrMissingExpense() {
        existing(99, 1);
        assertAllChangesRejected(404);
        assertCode(404, () -> service.read(10, 7, principal));

        when(expenses.findById(7L)).thenReturn(Optional.empty());
        assertAllChangesRejected(404);
        assertCode(404, () -> service.read(10, 7, principal));
    }

    @Test @DisplayName("종료된 모임의 영수증은 변경할 수 없다")
    void closedMeeting() {
        var closed = new MeetingAccessPort.Context(1, false, false, Set.of(1L));
        when(access.requireMember(10, principal)).thenReturn(closed);
        when(access.requireMemberForRead(10, principal)).thenReturn(closed);
        existing(10, 1);
        assertAllChangesRejected(409);
    }

    @Test @DisplayName("정산 확정 후에는 영수증을 변경할 수 없다")
    void closedSettlement() {
        Settlement settlement = new Settlement(10);
        settlement.close(1);
        when(settlements.findByMeetingId(10)).thenReturn(settlement);
        existing(10, 1);
        assertAllChangesRejected(409);
    }

    @Test @DisplayName("영수증 조회는 결제자가 아니어도, 모임 종료·정산 확정 후에도 가능")
    void read() {
        var closed = new MeetingAccessPort.Context(1, false, false, Set.of(1L, 2L));
        when(access.requireMemberForRead(10, principal)).thenReturn(closed);
        Settlement settlement = new Settlement(10);
        settlement.close(1);
        when(settlements.findByMeetingId(10)).thenReturn(settlement);
        Expense expense = existing(10, 2);
        expense.attachReceipt(OLD_KEY);
        byte[] image = {1, 2, 3};
        when(storage.read(OLD_KEY)).thenReturn(image);

        assertArrayEquals(image, service.read(10, 7, principal));
        verify(access, never()).requireMember(anyLong(), any());
    }

    private void assertAllChangesRejected(int code) {
        assertCode(code, () -> service.checkUpload(10, 7, principal));
        assertCode(code, () -> service.replace(10, 7, principal, NEW_KEY));
        assertCode(code, () -> service.replace(10, 7, principal, null));
        verify(storage, never()).deleteAfterCommit(any());
    }

    private Expense existing(long meetingId, long payerId) {
        Expense expense = Expense.register(meetingId, payerId, "기존", 100, null, Map.of(1L, 100L), "EXACT", null, null);
        ReflectionTestUtils.setField(expense, "id", 7L);
        when(expenses.findById(7L)).thenReturn(Optional.of(expense));
        return expense;
    }

    private void assertCode(int code, Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).getCode());
    }
}
