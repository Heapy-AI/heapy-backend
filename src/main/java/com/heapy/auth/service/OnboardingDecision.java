package com.heapy.auth.service;

public record OnboardingDecision(
        NextStep nextStep,
        int onboardingStep
) {
}
