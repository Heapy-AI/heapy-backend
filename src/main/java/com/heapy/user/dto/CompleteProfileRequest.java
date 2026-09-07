package com.heapy.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 마지막 온보딩 단계에서 전달하는 전체 필수 프로필.
 * @author 김진우
 */
public class CompleteProfileRequest extends UpdateProfileRequest {
    @Override @NotBlank
    public String getName() { return super.getName(); }

    @Override @NotNull
    public LocalDate getBirthDate() { return super.getBirthDate(); }

    @Override @NotBlank
    public String getSex() { return super.getSex(); }

    @Override @NotNull
    public BigDecimal getHeightCm() { return super.getHeightCm(); }

    @Override @NotNull
    public BigDecimal getWeightKg() { return super.getWeightKg(); }

    @Override @NotBlank
    public String getSmokingStatus() { return super.getSmokingStatus(); }

    @Override @NotBlank
    public String getAlcoholFrequency() { return super.getAlcoholFrequency(); }
}
