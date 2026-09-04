package com.heapy.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.heapy.terms.repository.UserTermsConsentRepository;
import com.heapy.user.domain.User;
import com.heapy.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnboardingDecisionServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserTermsConsentRepository consentRepository;

    private OnboardingDecisionService service;

    @BeforeEach
    void setUp() {
        service = new OnboardingDecisionService(userRepository, consentRepository);
    }

    @Test
    void 현재_필수_약관의_최신_버전_미동의가_있으면_약관_단계다() {
        User user = user(2, null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(consentRepository.countMissingCurrentRequiredConsents(USER_ID)).thenReturn(1L);

        OnboardingDecision decision = service.decide(USER_ID);

        assertThat(decision.nextStep()).isEqualTo(NextStep.TERMS);
        assertThat(decision.onboardingStep()).isEqualTo(2);
    }

    @Test
    void 필수_약관은_완료했지만_프로필이_미완료면_프로필_단계다() {
        User user = user(4, null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(consentRepository.countMissingCurrentRequiredConsents(USER_ID)).thenReturn(0L);

        assertThat(service.decide(USER_ID).nextStep()).isEqualTo(NextStep.PROFILE);
    }

    @Test
    void 필수_약관과_프로필이_모두_완료되면_홈_단계다() {
        User user = user(6, Instant.parse("2026-09-03T00:00:00Z"));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(consentRepository.countMissingCurrentRequiredConsents(USER_ID)).thenReturn(0L);

        assertThat(service.decide(USER_ID).nextStep()).isEqualTo(NextStep.HOME);
    }

    private User user(int onboardingStep, Instant completedAt) {
        User user = mock(User.class);
        lenient().when(user.getOnboardingStep()).thenReturn(onboardingStep);
        lenient().when(user.getOnboardingCompletedAt()).thenReturn(completedAt);
        return user;
    }
}
