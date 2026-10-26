package com.prgms.backend.domain.expense;

import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest;
import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest.Participant;
import com.prgms.backend.domain.expense.dto.ExpenseCreateRequest.SplitMode;
import com.prgms.backend.domain.expense.dto.ExpenseResponse;
import com.prgms.backend.domain.expense.entity.Expense;
import com.prgms.backend.domain.expense.entity.ExpenseRegistration;
import com.prgms.backend.domain.expense.repository.ExpenseRegistrationRepository;
import com.prgms.backend.domain.expense.repository.ExpenseRepository;
import com.prgms.backend.domain.expense.service.ExpenseRegistrationService;
import com.prgms.backend.domain.expense.service.ExpenseService;
import com.prgms.backend.domain.settlement.integration.MeetingAccessPort;
import com.prgms.backend.global.exception.BusinessException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpenseRegistrationServiceTest {
    private static final String KEY = "3F2504E0-4F89-11D3-9A0C-0305E82C3301";
    private static final String STORED_KEY = KEY.toLowerCase(Locale.ROOT);
    // 변환 전 Java 구현(69460b6 이전 ExpenseRegistrationService.fingerprint)으로 계산한 값입니다.
    // DB에 저장된 지문과 비교하므로 구현을 바꿔도 같은 요청은 같은 값이어야 합니다.
    private static final String EQUAL_FINGERPRINT = "e9bd728eae404ad08381a529f17771efc438d44ddec07227b7e1ed5e6d11285d";
    private static final String EXACT_FINGERPRINT = "4ee13e84da4c87d958187ce8f3779d5e217e1f021a87114f2b8cab2976df45cd";

    private final ExpenseService expenseService = mock(ExpenseService.class);
    private final MeetingAccessPort access = mock(MeetingAccessPort.class);
    private final ExpenseRepository expenses = mock(ExpenseRepository.class);
    private final ExpenseRegistrationRepository registrations = mock(ExpenseRegistrationRepository.class);
    private final Principal principal = () -> "user";
    private ValidatorFactory validation;
    private ExpenseRegistrationService service;

    @BeforeEach void setup() {
        validation = Validation.buildDefaultValidatorFactory();
        service = new ExpenseRegistrationService(expenseService, access, expenses, registrations, validation.getValidator());
        when(access.requireMember(10, principal)).thenReturn(new MeetingAccessPort.Context(1, false, true, Set.of(1L, 2L, 3L)));
        when(expenseService.create(eq(10L), eq(principal), any())).thenReturn(response(7));
        when(registrations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach void closeValidator() { validation.close(); }

    @Test @DisplayName("첫 요청은 지출을 등록하고 소문자 요청 번호·지문·지출 ID를 함께 저장")
    void firstRequest() {
        ExpenseResponse response = service.create(10, principal, equalRequest(), KEY);

        assertEquals(7L, response.getId());
        verify(expenseService).create(10, principal, equalRequest());
        ExpenseRegistration saved = savedRegistration();
        assertEquals(10L, saved.getMeetingId());
        assertEquals(1L, saved.getMemberId());
        assertEquals(STORED_KEY, saved.getRequestKey());
        assertEquals(7L, saved.getExpenseId());
        assertEquals(EQUAL_FINGERPRINT, saved.getFingerprint());
    }

    @Test @DisplayName("지문은 변환 전 Java 구현과 같고, 지수 표기(1e4)와 일반 표기의 금액은 같은 값이다")
    void fingerprintCompatibility() {
        service.create(10, principal, exactRequest(new BigDecimal("1E+4"), new BigDecimal("6E+3")), KEY);
        assertEquals(EXACT_FINGERPRINT, savedRegistration().getFingerprint());

        reset(registrations);
        when(registrations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.create(10, principal, exactRequest(BigDecimal.valueOf(10_000), BigDecimal.valueOf(6_000)), KEY);
        assertEquals(EXACT_FINGERPRINT, savedRegistration().getFingerprint());
    }

    @Test @DisplayName("같은 요청 번호로 같은 내용을 다시 보내면 새로 등록하지 않고 기존 지출을 반환")
    void retrySameRequest() {
        registered(EQUAL_FINGERPRINT);
        Expense expense = Expense.register(10, 1, "식사", 30_000, "메모",
                Map.of(1L, 10_000L, 2L, 10_000L, 3L, 10_000L), "EQUAL", 100, null);
        ReflectionTestUtils.setField(expense, "id", 7L);
        when(expenses.findById(7L)).thenReturn(Optional.of(expense));

        ExpenseResponse response = service.create(10, principal, equalRequest(), KEY);

        assertEquals(7L, response.getId());
        assertEquals(30_000L, response.getAmount());
        verify(expenseService, never()).create(anyLong(), any(), any());
        verify(registrations, never()).save(any());
    }

    @Test @DisplayName("같은 요청 번호로 내용이 다른 요청은 409")
    void retryChangedRequest() {
        registered(EQUAL_FINGERPRINT);
        assertCode(409, () -> service.create(10, principal, exactRequest(BigDecimal.valueOf(10_000), BigDecimal.valueOf(6_000)), KEY));
        verify(expenseService, never()).create(anyLong(), any(), any());
    }

    @Test @DisplayName("등록 후 삭제된 지출을 같은 요청 번호로 다시 보내면 복원하지 않고 410")
    void retryDeletedExpense() {
        registered(EQUAL_FINGERPRINT);
        when(expenses.findById(7L)).thenReturn(Optional.empty());
        assertCode(410, () -> service.create(10, principal, equalRequest(), KEY));
        verify(expenseService, never()).create(anyLong(), any(), any());
    }

    @Test @DisplayName("모임 ID나 요청 번호 형식이 잘못되면 모임 잠금 전에 400")
    void invalidKeyOrMeeting() {
        for (String key : new String[] {null, "", "not-a-uuid", KEY + "0", KEY.replace('-', '_')}) {
            assertCode(400, () -> service.create(10, principal, equalRequest(), key));
        }
        assertCode(400, () -> service.create(0, principal, equalRequest(), KEY));
        verifyNoInteractions(access, expenseService, registrations);
    }

    @Test @DisplayName("요청 본문이 없거나 검증에 실패하면 400이고 등록하지 않는다")
    void invalidRequest() {
        var blankTitle = new ExpenseCreateRequest(" ", BigDecimal.TEN, null, SplitMode.EQUAL, 1, null,
                List.of(new Participant(1L, null)), false);
        assertCode(400, () -> service.create(10, principal, null, KEY));
        assertCode(400, () -> service.create(10, principal, blankTitle, KEY));
        verify(expenseService, never()).create(anyLong(), any(), any());
        verify(registrations, never()).save(any());
    }

    private void registered(String fingerprint) {
        when(registrations.findByMeetingIdAndMemberIdAndRequestKey(10L, 1L, STORED_KEY))
                .thenReturn(new ExpenseRegistration(10, 1, STORED_KEY, fingerprint, 7));
    }

    private ExpenseRegistration savedRegistration() {
        var captor = ArgumentCaptor.forClass(ExpenseRegistration.class);
        verify(registrations).save(captor.capture());
        return captor.getValue();
    }

    private ExpenseCreateRequest equalRequest() {
        return new ExpenseCreateRequest("식사", BigDecimal.valueOf(30_000), "메모", SplitMode.EQUAL, 100, 1L,
                List.of(new Participant(1L, null), new Participant(2L, null), new Participant(3L, null)), false);
    }

    private ExpenseCreateRequest exactRequest(BigDecimal total, BigDecimal secondShare) {
        return new ExpenseCreateRequest("택시", total, null, SplitMode.EXACT, null, null,
                List.of(new Participant(1L, BigDecimal.valueOf(4_000)), new Participant(2L, secondShare)), false);
    }

    private ExpenseResponse response(long id) {
        return new ExpenseResponse(id, 10L, 1L, "식사", 30_000, "메모", "EQUAL", 100, 1L,
                List.of(new ExpenseResponse.Share(1, 10_000)), null, false);
    }

    private void assertCode(int code, Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).getCode());
    }
}
