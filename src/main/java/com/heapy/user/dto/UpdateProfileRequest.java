package com.heapy.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class UpdateProfileRequest {

    @Size(max = 50)
    private String name;
    private boolean namePresent;

    @PastOrPresent
    private LocalDate birthDate;
    private boolean birthDatePresent;

    @Pattern(regexp = "Male|Female")
    private String sex;
    private boolean sexPresent;

    @DecimalMin("30.0")
    @DecimalMax("250.0")
    private BigDecimal heightCm;
    private boolean heightCmPresent;

    @DecimalMin("2.0")
    @DecimalMax("500.0")
    private BigDecimal weightKg;
    private boolean weightKgPresent;

    @Pattern(regexp = "never|former|current")
    private String smokingStatus;
    private boolean smokingStatusPresent;

    @Pattern(regexp = "none|monthly_1_2|weekly_1_2|weekly_3_plus")
    private String alcoholFrequency;
    private boolean alcoholFrequencyPresent;

    private List<@Valid ProfileOptionRequest> chronicConditions;
    private boolean chronicConditionsPresent;

    private List<@Valid ProfileOptionRequest> allergies;
    private boolean allergiesPresent;

    @Size(max = 1000)
    private String healthCautions;
    private boolean healthCautionsPresent;

    @Min(1)
    @Max(6)
    private Integer onboardingStep;
    private boolean onboardingStepPresent;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    public boolean hasName() {
        return namePresent;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
        this.birthDatePresent = true;
    }

    public boolean hasBirthDate() {
        return birthDatePresent;
    }

    public String getSex() {
        return sex;
    }

    public void setSex(String sex) {
        this.sex = sex;
        this.sexPresent = true;
    }

    public boolean hasSex() {
        return sexPresent;
    }

    public BigDecimal getHeightCm() {
        return heightCm;
    }

    public void setHeightCm(BigDecimal heightCm) {
        this.heightCm = heightCm;
        this.heightCmPresent = true;
    }

    public boolean hasHeightCm() {
        return heightCmPresent;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(BigDecimal weightKg) {
        this.weightKg = weightKg;
        this.weightKgPresent = true;
    }

    public boolean hasWeightKg() {
        return weightKgPresent;
    }

    public String getSmokingStatus() {
        return smokingStatus;
    }

    public void setSmokingStatus(String smokingStatus) {
        this.smokingStatus = smokingStatus;
        this.smokingStatusPresent = true;
    }

    public boolean hasSmokingStatus() {
        return smokingStatusPresent;
    }

    public String getAlcoholFrequency() {
        return alcoholFrequency;
    }

    public void setAlcoholFrequency(String alcoholFrequency) {
        this.alcoholFrequency = alcoholFrequency;
        this.alcoholFrequencyPresent = true;
    }

    public boolean hasAlcoholFrequency() {
        return alcoholFrequencyPresent;
    }

    public List<ProfileOptionRequest> getChronicConditions() {
        return chronicConditions;
    }

    public void setChronicConditions(List<ProfileOptionRequest> chronicConditions) {
        this.chronicConditions = chronicConditions;
        this.chronicConditionsPresent = true;
    }

    public boolean hasChronicConditions() {
        return chronicConditionsPresent;
    }

    public List<ProfileOptionRequest> getAllergies() {
        return allergies;
    }

    public void setAllergies(List<ProfileOptionRequest> allergies) {
        this.allergies = allergies;
        this.allergiesPresent = true;
    }

    public boolean hasAllergies() {
        return allergiesPresent;
    }

    public String getHealthCautions() {
        return healthCautions;
    }

    public void setHealthCautions(String healthCautions) {
        this.healthCautions = healthCautions;
        this.healthCautionsPresent = true;
    }

    public boolean hasHealthCautions() {
        return healthCautionsPresent;
    }

    public Integer getOnboardingStep() {
        return onboardingStep;
    }

    public void setOnboardingStep(Integer onboardingStep) {
        this.onboardingStep = onboardingStep;
        this.onboardingStepPresent = true;
    }

    public boolean hasOnboardingStep() {
        return onboardingStepPresent;
    }
}
