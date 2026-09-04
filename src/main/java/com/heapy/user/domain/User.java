package com.heapy.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users", schema = "public")
public class User {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "onboarding_step", nullable = false)
    private int onboardingStep;

    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    protected User() {
    }

    public UUID getUserId() {
        return userId;
    }

    public int getOnboardingStep() {
        return onboardingStep;
    }

    public Instant getOnboardingCompletedAt() {
        return onboardingCompletedAt;
    }
}
