package com.heapy.auth.service;

import com.heapy.terms.repository.UserTermsConsentRepository;
import com.heapy.user.domain.User;
import com.heapy.user.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingDecisionService {

    @Value("${heapy.onboarding.require-consents:true}")
    private boolean requireConsents = true;

    private final UserRepository userRepository;
    private final UserTermsConsentRepository consentRepository;

    public OnboardingDecisionService(
            UserRepository userRepository,
            UserTermsConsentRepository consentRepository
    ) {
        this.userRepository = userRepository;
        this.consentRepository = consentRepository;
    }

    @Transactional(readOnly = true)
    public OnboardingDecision decide(UUID userId) {
        Optional<User> user = userRepository.findById(userId);
        int onboardingStep = user.map(User::getOnboardingStep).orElse(1);
        if (requireConsents && consentRepository.countMissingCurrentRequiredConsents(userId) > 0) {
            return new OnboardingDecision(NextStep.TERMS, onboardingStep);
        }
        if (user.isEmpty() || user.get().getOnboardingCompletedAt() == null) {
            return new OnboardingDecision(NextStep.PROFILE, onboardingStep);
        }
        return new OnboardingDecision(NextStep.HOME, onboardingStep);
    }
}
