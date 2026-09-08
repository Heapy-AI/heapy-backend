package com.heapy.checkup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class OcrModels {
    private OcrModels() { }

    public record Job(UUID id, UUID userId, String inputType, String status, Integer pageCount,
            String errorCode, Instant createdAt, Instant expiresAt, String extension,
            long sourceSize, String sourceHash) { }
    public record JobResponse(UUID jobId, String documentType, String status, Integer pageCount,
            Instant expiresAt, int pollAfterMs, JsonNode result, String errorCode) { }
    public record Snapshot(String status, Integer pageCount, JsonNode result, String errorCode) { }
    public record Receipt(String hash, String response) { }
    public record Confirmation(@NotNull LocalDate measuredAt,
            @Size(max = 200) String providerName,
            @NotEmpty @Size(max = 200) List<@Valid Result> results,
            @NotNull @Size(max = 600) List<@Valid Correction> corrections) { }
    public record Result(@NotBlank @Size(max = 100) String itemCode,
            @NotBlank @Size(max = 2000) String value,
            @Digits(integer = 18, fraction = 8) BigDecimal numericValue,
            @Size(max = 100) String unit, @Size(max = 500) String status) { }
    public record Correction(@NotBlank @Size(max = 200) String fieldKey,
            @Size(max = 100) String itemCode, @Size(max = 2000) String originalValue,
            @Size(max = 2000) String correctedValue, @NotBlank @Size(max = 20) String correctionType) { }
    public record Confirmed(UUID recordId, String sourceType, int resultCount, Instant confirmedAt) { }
}
