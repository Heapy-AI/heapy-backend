package com.heapy.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heapy.auth.client.SupabaseAuthClient;
import com.heapy.auth.client.SupabaseAuthClientException;
import com.heapy.auth.client.SupabaseAuthSession;
import com.heapy.auth.dto.LoginRequest;
import com.heapy.auth.dto.LoginResponse;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final UUID USER_ID = UUID.fromString("9cf0cf52-b838-4f29-9756-858d45038ca5");

    @Mock
    private SupabaseAuthClient authClient;

    @Mock
    private OnboardingDecisionService onboardingDecisionService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(authClient, onboardingDecisionService);
    }

    @Test
    void 정상_로그인_후_약관_단계를_반환한다() {
        assertNextStep(NextStep.TERMS, 1);
    }

    @Test
    void 정상_로그인_후_프로필_단계를_반환한다() {
        assertNextStep(NextStep.PROFILE, 3);
    }

    @Test
    void 정상_로그인_후_홈_단계를_반환한다() {
        assertNextStep(NextStep.HOME, 6);
    }

    @Test
    void 이메일의_앞뒤_공백을_제거하고_소문자로_변환한다() {
        LoginRequest request = new LoginRequest("  HEAPY@EXAMPLE.COM  ", "SecurePassword123!");
        when(authClient.login("heapy@example.com", "SecurePassword123!")).thenReturn(verifiedSession());
        when(onboardingDecisionService.decide(USER_ID))
                .thenReturn(new OnboardingDecision(NextStep.HOME, 6));

        authService.login(request);

        verify(authClient).login("heapy@example.com", "SecurePassword123!");
    }

    @Test
    void 잘못된_자격증명은_AUTH_001로_변환한다() {
        when(authClient.login("heapy@example.com", "wrong-password"))
                .thenThrow(new SupabaseAuthClientException(
                        SupabaseAuthClientException.Reason.INVALID_CREDENTIALS
                ));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("heapy@example.com", "wrong-password")
        )).isInstanceOfSatisfying(HeapyException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS)
        );
    }

    @Test
    void 이메일_미인증_사용자는_AUTH_002로_차단한다() {
        SupabaseAuthSession unverified = new SupabaseAuthSession(
                "access-secret",
                "refresh-secret",
                "Bearer",
                3600,
                Instant.parse("2026-09-03T10:30:00Z"),
                USER_ID,
                "heapy@example.com",
                false
        );
        when(authClient.login("heapy@example.com", "SecurePassword123!")).thenReturn(unverified);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("heapy@example.com", "SecurePassword123!")
        )).isInstanceOfSatisfying(HeapyException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED)
        );
    }

    @Test
    void Supabase_장애는_AUTH_009로_변환한다() {
        when(authClient.login("heapy@example.com", "SecurePassword123!"))
                .thenThrow(new SupabaseAuthClientException(
                        SupabaseAuthClientException.Reason.PROVIDER_UNAVAILABLE
                ));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("heapy@example.com", "SecurePassword123!")
        )).isInstanceOfSatisfying(HeapyException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_PROVIDER_UNAVAILABLE)
        );
    }

    @Test
    void 비밀번호와_토큰은_toString에_노출하지_않는다() {
        LoginRequest request = new LoginRequest("heapy@example.com", "SecurePassword123!");
        SupabaseAuthSession session = verifiedSession();

        assertThat(request.toString()).doesNotContain("SecurePassword123!");
        assertThat(session.toString()).doesNotContain("access-secret", "refresh-secret");
    }

    private void assertNextStep(NextStep nextStep, int onboardingStep) {
        when(authClient.login("heapy@example.com", "SecurePassword123!")).thenReturn(verifiedSession());
        when(onboardingDecisionService.decide(USER_ID))
                .thenReturn(new OnboardingDecision(nextStep, onboardingStep));

        LoginResponse response = authService.login(
                new LoginRequest("heapy@example.com", "SecurePassword123!")
        );

        assertThat(response.nextStep()).isEqualTo(nextStep.value());
        assertThat(response.onboardingStep()).isEqualTo(onboardingStep);
    }

    private SupabaseAuthSession verifiedSession() {
        return new SupabaseAuthSession(
                "access-secret",
                "refresh-secret",
                "Bearer",
                3600,
                Instant.parse("2026-09-03T10:30:00Z"),
                USER_ID,
                "heapy@example.com",
                true
        );
    }
}
