package com.heapy.checkup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
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
            long sourceSize, String sourceHash, String documentType) {
        public Job(UUID id, UUID userId, String inputType, String status, Integer pageCount,
                String errorCode, Instant createdAt, Instant expiresAt, String extension,
                long sourceSize, String sourceHash) {
            this(id, userId, inputType, status, pageCount, errorCode, createdAt, expiresAt,
                    extension, sourceSize, sourceHash, "health_checkup");
        }
    }
    public record JobResponse(UUID jobId, String documentType, String status, Integer pageCount,
            Instant expiresAt, int pollAfterMs, JsonNode result, String errorCode) { }
    public record Snapshot(String status, Integer pageCount, JsonNode result, String errorCode) { }
    public record Receipt(String hash, String response) { }
    public record Confirmation(@NotNull LocalDate measuredAt,
            @Size(max = 200) String providerName,
            @Size(max = 200) List<@NotNull @Valid Result> results,
            @Size(max = 600) List<@NotNull @Valid Correction> corrections,
            Integer reviewVersion,
            @Size(max = 50) List<@NotNull @Valid Finding> findings,
            @Size(max = 20) List<@NotNull @Valid Finding> overallOpinions,
            @Size(max = 470) List<@NotBlank @Size(max = 100) String> excludedFieldKeys) {
        public Confirmation {
            results = results == null ? List.of() : results;
            corrections = corrections == null ? List.of() : corrections;
            findings = findings == null ? List.of() : findings;
            overallOpinions = overallOpinions == null ? List.of() : overallOpinions;
            excludedFieldKeys = excludedFieldKeys == null ? List.of() : excludedFieldKeys;
        }
        public Confirmation(LocalDate measuredAt, String providerName, List<Result> results, List<Correction> corrections) {
            this(measuredAt, providerName, results, corrections, null, List.of(), List.of(), List.of());
        }
    }
    public record Result(@NotBlank @Size(max = 100) String itemCode,
            @NotBlank @Size(max = 2000) String value,
            @Digits(integer = 18, fraction = 8) BigDecimal numericValue,
            @Size(max = 100) String unit, @Size(max = 500) String status,
            @Size(max = 100) String fieldKey) {
        public Result(String itemCode, String value, BigDecimal numericValue, String unit, String status) {
            this(itemCode, value, numericValue, unit, status, null);
        }
    }
    public record Finding(@NotNull Integer schemaVersion, @NotNull UUID findingId,
            @NotBlank String classification, String examType,
            @NotBlank @Size(max = 200) String examName,
            @NotBlank @Size(max = 12000) String text,
            @Size(max = 200) String bodySite, @Size(max = 200) String method,
            LocalDate performedAt, @Valid Summary summary) {
        public Finding withoutSummary() {
            return new Finding(schemaVersion, findingId, classification, examType, examName, text,
                    bodySite, method, performedAt, null);
        }
    }
    public record Summary(@NotBlank @Size(max = 2000) String text, @NotBlank String source,
            @NotBlank String basisHash) { }
    public record Validated(Confirmation confirmation, List<Correction> corrections) { }
    public record Detail(UUID recordId, LocalDate measuredAt, String providerName,
            List<DetailResult> results, List<Finding> findings, List<Finding> overallOpinions) { }
    public record DetailResult(String itemCode, String itemName, String value, BigDecimal numericValue,
            String unit, String status) { }
    public record Correction(@NotBlank @Size(max = 200) String fieldKey,
            @Size(max = 100) String itemCode, @Size(max = 2000) String originalValue,
            @Size(max = 2000) String correctedValue, @NotBlank @Size(max = 20) String correctionType) { }
    public record Confirmed(UUID recordId, String sourceType, int resultCount, Instant confirmedAt,
            int findingCount, int overallOpinionCount) {
        public Confirmed(UUID recordId, String sourceType, int resultCount, Instant confirmedAt) {
            this(recordId, sourceType, resultCount, confirmedAt, 0, 0);
        }
    }
}
