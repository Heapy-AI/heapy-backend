package com.heapy.user.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users", schema = "public")
public class User {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "name")
    private String name;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "sex")
    private String sex;

    @Column(name = "height_cm", precision = 5, scale = 2)
    private BigDecimal heightCm;

    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "smoking_status")
    private String smokingStatus;

    @Column(name = "alcohol_frequency")
    private String alcoholFrequency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chronic_conditions", nullable = false, columnDefinition = "jsonb")
    private JsonNode chronicConditions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allergies", nullable = false, columnDefinition = "jsonb")
    private JsonNode allergies;

    @Column(name = "health_cautions")
    private String healthCautions;

    @Column(name = "onboarding_step", nullable = false)
    private short onboardingStep;

    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(UUID userId, JsonNode emptyArray, Instant now) {
        this.userId = userId;
        this.chronicConditions = emptyArray.deepCopy();
        this.allergies = emptyArray.deepCopy();
        this.onboardingStep = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getSex() {
        return sex;
    }

    public BigDecimal getHeightCm() {
        return heightCm;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public String getSmokingStatus() {
        return smokingStatus;
    }

    public String getAlcoholFrequency() {
        return alcoholFrequency;
    }

    public JsonNode getChronicConditions() {
        return chronicConditions;
    }

    public JsonNode getAllergies() {
        return allergies;
    }

    public String getHealthCautions() {
        return healthCautions;
    }

    public int getOnboardingStep() {
        return onboardingStep;
    }

    public Instant getOnboardingCompletedAt() {
        return onboardingCompletedAt;
    }

    public void updateName(String name) {
        this.name = name;
    }

    public void updateBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public void updateSex(String sex) {
        this.sex = sex;
    }

    public void updateHeightCm(BigDecimal heightCm) {
        this.heightCm = heightCm;
    }

    public void updateWeightKg(BigDecimal weightKg) {
        this.weightKg = weightKg;
    }

    public void updateSmokingStatus(String smokingStatus) {
        this.smokingStatus = smokingStatus;
    }

    public void updateAlcoholFrequency(String alcoholFrequency) {
        this.alcoholFrequency = alcoholFrequency;
    }

    public void updateChronicConditions(JsonNode chronicConditions) {
        this.chronicConditions = chronicConditions;
    }

    public void updateAllergies(JsonNode allergies) {
        this.allergies = allergies;
    }

    public void updateHealthCautions(String healthCautions) {
        this.healthCautions = healthCautions;
    }

    public void advanceOnboardingStep(int onboardingStep) {
        if (onboardingStep < this.onboardingStep) {
            throw new IllegalArgumentException("온보딩 단계는 이전 단계로 되돌릴 수 없습니다.");
        }
        this.onboardingStep = (short) onboardingStep;
    }

    public void completeOnboarding(Instant completedAt) {
        this.onboardingStep = 6;
        this.onboardingCompletedAt = completedAt;
        this.updatedAt = completedAt;
    }

    public void markUpdated(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
