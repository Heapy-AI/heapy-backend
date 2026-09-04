package com.heapy.terms.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ConsentItemRequest(
        @NotNull Long termsId,
        @NotNull @Pattern(regexp = "agreed|revoked") String action
) {
}
