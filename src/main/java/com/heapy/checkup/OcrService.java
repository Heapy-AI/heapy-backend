package com.heapy.checkup;

import com.heapy.checkup.OcrModels.Confirmation;
import com.heapy.checkup.OcrModels.Confirmed;
import com.heapy.checkup.OcrModels.Job;
import com.heapy.checkup.OcrModels.JobResponse;
import com.heapy.checkup.OcrModels.Snapshot;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class OcrService {
    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private final OcrRepository repository;
    private final OcrGateway gateway;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final OcrProperties properties;

    public OcrService(OcrRepository repository, OcrGateway gateway, PlatformTransactionManager manager,
            Clock clock, OcrProperties properties) {
        this.repository = repository;
        this.gateway = gateway;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
        this.properties = properties;
    }

    public JobResponse upload(UUID user, UUID key, String inputType, MultipartFile file) {
        if (!properties.enabled()) throw new HeapyException(ErrorCode.OCR_UNAVAILABLE);
        if (!Set.of("camera", "image", "pdf").contains(inputType)) throw new HeapyException(ErrorCode.INVALID_INPUT);
        byte[] bytes;
        try (var input = file.getInputStream()) {
            if (file.getSize() > 20_000_000) throw new HeapyException(ErrorCode.OCR_FILE_LIMIT);
            bytes = input.readNBytes(20_000_001);
        } catch (IOException exception) { throw new HeapyException(ErrorCode.OCR_UNAVAILABLE); }
        if (bytes.length == 0 || bytes.length > 20_000_000) throw new HeapyException(ErrorCode.OCR_FILE_LIMIT);
        String extension = extension(bytes);
        if (("pdf".equals(extension)) != ("pdf".equals(inputType))) throw new HeapyException(ErrorCode.OCR_UNSUPPORTED_FILE);
        String sourceHash = OcrJson.hash(bytes);
        String hash = OcrJson.hash(OcrJson.encode(Map.of("inputType", inputType, "sha256", sourceHash)));
        try {
            return transaction.execute(status -> {
                if (!repository.lockUser(user)) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
                var receipt = repository.receipt(user, "upload", key);
                if (receipt.isPresent()) {
                    if (!receipt.get().hash().equals(hash)) throw new HeapyException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                    return OcrJson.MAPPER.readValue(receipt.get().response(), JobResponse.class);
                }
                if (repository.activeCount(user) >= 2) throw new HeapyException(ErrorCode.OCR_UNAVAILABLE);
                Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                Job job = new Job(UUID.randomUUID(), user, inputType, "pending", null, null,
                        now, now.plusSeconds(600), extension, bytes.length, sourceHash);
                gateway.prepare(job, bytes);
                repository.insert(job, key);
                JobResponse response = response(job, null);
                repository.saveReceipt(job, "upload", key, hash, response);
                return response;
            });
        } catch (SdkException exception) { throw new HeapyException(ErrorCode.OCR_UNAVAILABLE); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    public JobResponse get(UUID user, UUID id) {
        Job job = owned(user, id);
        requireActive(job);
        if ("failed".equals(job.status())) return response(job, null);
        Snapshot snapshot = read(job);
        return transaction.execute(status -> {
            repository.lockJob(user, id);
            requireActive(owned(user, id));
            repository.progress(job, snapshot, publicError(snapshot.errorCode()));
            Job latest = owned(user, id);
            requireActive(latest);
            if (!clock.instant().isBefore(latest.expiresAt())) throw new HeapyException(ErrorCode.OCR_EXPIRED);
            return response(latest, "review".equals(latest.status()) ? snapshot : null);
        });
    }

    public Confirmed confirm(UUID user, UUID id, UUID key, Confirmation body) {
        if (body.measuredAt().isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))) {
            throw new HeapyException(ErrorCode.OCR_INVALID_RESULT);
        }
        String hash = OcrJson.hash(id + ":" + OcrJson.encode(body));
        Confirmed confirmed = transaction.execute(status -> {
            if (!repository.lockUser(user)) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
            var receipt = repository.receipt(user, "confirm", key);
            if (receipt.isPresent()) {
                if (!receipt.get().hash().equals(hash)) throw new HeapyException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                return OcrJson.MAPPER.readValue(receipt.get().response(), Confirmed.class);
            }
            repository.lockJob(user, id);
            Job job = owned(user, id);
            if ("confirmed".equals(job.status())) throw new HeapyException(ErrorCode.OCR_ALREADY_CONFIRMED);
            requireActive(job);
            if ("failed".equals(job.status())) throw new HeapyException(ErrorCode.OCR_INVALID_RESULT);
            Snapshot snapshot = read(job);
            if (!"completed".equals(snapshot.status())) throw new HeapyException(ErrorCode.OCR_INVALID_RESULT);
            var corrections = OcrConfirmationValidator.validate(snapshot.result(), body, repository::activeItem);
            if (!clock.instant().isBefore(job.expiresAt())) throw new HeapyException(ErrorCode.OCR_EXPIRED);
            String fingerprint = OcrJson.hash(OcrJson.encode(Map.of("measuredAt", body.measuredAt(),
                    "providerName", body.providerName() == null ? "" : body.providerName(), "results", body.results())));
            if (repository.duplicate(user, fingerprint)) throw new HeapyException(ErrorCode.OCR_ALREADY_CONFIRMED);
            Confirmed response = repository.confirm(job, body, fingerprint, corrections, clock.instant());
            repository.saveReceipt(job, "confirm", key, hash, response);
            return response;
        });
        cleanup(owned(user, id));
        return confirmed;
    }

    public void cancel(UUID user, UUID id) {
        transaction.executeWithoutResult(status -> {
            repository.lockJob(user, id);
            Job job = owned(user, id);
            if (!"confirmed".equals(job.status())) repository.close(job, "expired");
        });
        cleanup(owned(user, id));
    }

    void cleanup(Job job) {
        try {
            gateway.purge(job, "confirmed".equals(job.status()) ? "confirmed" : "cancelled");
            repository.cleanupDone(job.id());
        } catch (Exception exception) {
            log.warn("OCR 임시 결과 정리 재시도 필요: jobId={}, exceptionType={}", job.id(), exception.getClass().getSimpleName());
            try { repository.deferCleanup(job.id()); }
            catch (Exception retryException) { log.error("OCR 정리 재시도 시각 저장 실패: jobId={}", job.id()); }
        }
    }

    private Snapshot read(Job job) {
        try { return gateway.read(job); }
        catch (SdkException exception) { throw new HeapyException(ErrorCode.OCR_UNAVAILABLE); }
    }

    private Job owned(UUID user, UUID id) {
        return repository.owned(user, id).orElseThrow(() -> new HeapyException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireActive(Job job) {
        if (!clock.instant().isBefore(job.expiresAt()) || Set.of("expired", "confirmed").contains(job.status())) {
            throw new HeapyException(ErrorCode.OCR_EXPIRED);
        }
    }

    private JobResponse response(Job job, Snapshot snapshot) {
        return new JobResponse(job.id(), "health_checkup", "review".equals(job.status()) ? "completed" : job.status(),
                job.pageCount(), job.expiresAt(), 2500, snapshot == null ? null : snapshot.result(), job.errorCode());
    }

    static String publicError(String internal) {
        if (internal == null) return null;
        return switch (internal) {
            case "FILE_LIMIT", "PAGE_LIMIT" -> "OCR-001";
            case "UNSUPPORTED_FORMAT", "ENCRYPTED_PDF_UNSUPPORTED" -> "OCR-002";
            case "EXPIRED" -> "OCR-003";
            case "CORRUPT_FILE", "IMAGE_EXPANSION_LIMIT", "EXTRACTION_FAILED" -> "OCR-005";
            default -> "AI-001";
        };
    }

    static String extension(byte[] bytes) {
        if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') return "pdf";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) return "jpg";
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), png)) return "png";
        throw new HeapyException(ErrorCode.OCR_UNSUPPORTED_FILE);
    }
}
