package com.heapy.terms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SaveConsentsRequest(
        @NotEmpty @Size(max = 20) List<@Valid ConsentItemRequest> consents,
        @Pattern(regexp = "app|web|admin") String consentSource
) {
    public SaveConsentsRequest {
        consentSource = consentSource == null ? "app" : consentSource;
    }
}
