package com.heapy.auth.service;

import com.heapy.auth.client.SupabaseAuthClient;
import com.heapy.auth.client.SupabaseAuthClientException;
import com.heapy.auth.client.SupabaseAuthSession;
import com.heapy.auth.dto.LoginRequest;
import com.heapy.auth.dto.LoginResponse;
import com.heapy.auth.dto.LoginUserResponse;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final SupabaseAuthClient authClient;
    private final OnboardingDecisionService onboardingDecisionService;

    public AuthService(
            SupabaseAuthClient authClient,
            OnboardingDecisionService onboardingDecisionService
    ) {
        this.authClient = authClient;
        this.onboardingDecisionService = onboardingDecisionService;
    }

    public LoginResponse login(LoginRequest request) {
        SupabaseAuthSession session;
        try {
            session = authClient.login(request.email(), request.password());
        } catch (SupabaseAuthClientException exception) {
            throw mapException(exception);
        }
        return response(session);
    }

    /** 갱신 실패와 연결 장애를 구분하여 일시 장애로 로그아웃하지 않는다. @author 김진우 */
    public LoginResponse refresh(String refreshToken) {
        try {
            return response(authClient.refresh(refreshToken));
        } catch (SupabaseAuthClientException exception) {
            if (exception.getReason() == SupabaseAuthClientException.Reason.INVALID_CREDENTIALS) {
                throw new HeapyException(ErrorCode.INVALID_ACCESS_TOKEN);
            }
            throw mapException(exception);
        }
    }

    private LoginResponse response(SupabaseAuthSession session) {
        if (!session.emailVerified()) {
            throw new HeapyException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        OnboardingDecision decision = onboardingDecisionService.decide(session.userId());
        return new LoginResponse(
                session.accessToken(),
                session.refreshToken(),
                session.tokenType(),
                session.expiresIn(),
                session.expiresAt(),
                new LoginUserResponse(session.userId(), session.email(), true),
                decision.nextStep().value(),
                decision.onboardingStep()
        );
    }

    private HeapyException mapException(SupabaseAuthClientException exception) {
        return switch (exception.getReason()) {
            case INVALID_CREDENTIALS -> new HeapyException(ErrorCode.INVALID_CREDENTIALS);
            case EMAIL_NOT_VERIFIED -> new HeapyException(ErrorCode.EMAIL_NOT_VERIFIED);
            case RATE_LIMITED -> new HeapyException(ErrorCode.AUTH_RATE_LIMITED);
            case PROVIDER_UNAVAILABLE -> new HeapyException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
        };
    }
}
