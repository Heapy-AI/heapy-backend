package com.heapy.checkup;

import com.heapy.checkup.OcrModels.Job;
import com.heapy.checkup.OcrModels.Snapshot;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import tools.jackson.databind.JsonNode;

/**
 * 기존 EC2 역할로 비공개 임시 저장소와 Lambda 별칭만 호출한다.
 *
 * @author 김진우
 */
@Component
public class AwsOcrGateway implements OcrGateway {
    private final OcrProperties properties;
    private final S3Client s3;
    private final LambdaClient lambda;

    public AwsOcrGateway(OcrProperties properties) {
        this.properties = properties;
        Region region = Region.of(properties.region());
        s3 = S3Client.builder().region(region)
                .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(15)).maxConnections(8))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(20))
                        .retryPolicy(RetryPolicy.none())).build();
        lambda = LambdaClient.builder().region(region)
                .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(650)).maxConnections(4))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(670))
                        .retryPolicy(RetryPolicy.none())).build();
    }

    Map<String, Object> request(Job job) {
        return Map.of("contractVersion", "1.0", "jobId", job.id().toString(),
                "documentType", job.documentType(), "inputType", job.inputType(),
                "createdAt", job.createdAt().toString(), "expiresAt", job.expiresAt().toString(),
                "source", Map.of("bucket", properties.bucket(), "key", "originals/" + job.id() + "/source",
                        "extension", job.extension(), "sizeBytes", job.sourceSize(), "sha256", job.sourceHash()));
    }

    @Override
    public void prepare(Job job, byte[] source) {
        requireEnabled();
        var request = request(job);
        s3.putObject(b -> b.bucket(properties.bucket()).expectedBucketOwner(properties.accountId())
                        .key("originals/" + job.id() + "/source").ifNoneMatch("*")
                        .serverSideEncryption(ServerSideEncryption.AES256).cacheControl("no-store"),
                RequestBody.fromBytes(source));
        String state = OcrJson.encode(Map.of("request", request,
                "fingerprint", OcrJson.hash(OcrJson.encode(request)), "status", "pending"));
        s3.putObject(b -> b.bucket(properties.bucket()).expectedBucketOwner(properties.accountId())
                        .key("jobs/" + job.id() + ".json").ifNoneMatch("*")
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .contentType("application/json").cacheControl("no-store"), RequestBody.fromString(state));
    }

    @Override
    public Snapshot read(Job job) {
        requireEnabled();
        // 작성자: 김진우 — 처리 중 Lambda 동시성 슬롯을 점유하지 않고 허용된 제어 객체를 읽는다.
        try (var stream = s3.getObject(b -> b.bucket(properties.bucket())
                .expectedBucketOwner(properties.accountId()).key("jobs/" + job.id() + ".json"))) {
            byte[] bytes = stream.readNBytes(524289);
            if (bytes.length > 524288) throw new HeapyException(ErrorCode.OCR_UNAVAILABLE);
            JsonNode state = OcrJson.MAPPER.readTree(bytes);
            String expected = OcrJson.encode(request(job));
            if (!expected.equals(OcrJson.encode(state.path("request")))
                    || !OcrJson.hash(expected).equals(state.path("fingerprint").asText())) {
                throw new HeapyException(ErrorCode.OCR_UNAVAILABLE);
            }
            JsonNode result = state.path("result");
            return new Snapshot(state.path("status").asText(),
                    result.path("pageCount").isIntegralNumber() ? result.path("pageCount").asInt() : null,
                    "completed".equals(state.path("status").asText()) ? result.path("result") : null,
                    text(state.path("errorCode")));
        } catch (IOException exception) {
            throw new HeapyException(ErrorCode.OCR_UNAVAILABLE);
        }
    }

    @Override
    public Snapshot execute(Job job) {
        JsonNode response = invoke(request(job), false);
        if (response.path("error").path("retryable").asBoolean(false)) {
            throw new HeapyException(ErrorCode.OCR_UNAVAILABLE);
        }
        return new Snapshot(response.path("status").asText(),
                response.path("pageCount").isIntegralNumber() ? response.path("pageCount").asInt() : null,
                null, text(response.path("error").path("code")));
    }

    @Override
    public void purge(Job job, String reason) {
        JsonNode response = invoke(Map.of("contractVersion", "1.0", "operation", "purge",
                "request", request(job), "reason", reason), true);
        if (!response.path("error").isNull() && !response.path("error").isMissingNode()) {
            if ("JOB_NOT_FOUND".equals(response.path("error").path("code").asText())) return;
            throw new HeapyException(ErrorCode.OCR_UNAVAILABLE);
        }
        if (!Set.of("confirmed", "cancelled", "expired").contains(response.path("status").asText())) {
            throw new HeapyException(ErrorCode.OCR_UNAVAILABLE);
        }
    }

    private JsonNode invoke(Object payload, boolean control) {
        requireEnabled();
        var response = lambda.invoke(b -> {
            b.functionName(properties.workerArn()).invocationType(InvocationType.REQUEST_RESPONSE)
                    .payload(SdkBytes.fromUtf8String(OcrJson.encode(payload)));
            if (control) b.overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(8)));
        });
        if (response.statusCode() != 200 || response.functionError() != null
                || response.payload().asByteArray().length > 524288) {
            throw new HeapyException(ErrorCode.OCR_UNAVAILABLE);
        }
        JsonNode result = OcrJson.MAPPER.readTree(response.payload().asUtf8String());
        if (!"1.0".equals(result.path("contractVersion").asText())) throw new HeapyException(ErrorCode.OCR_UNAVAILABLE);
        return result;
    }

    private String text(JsonNode node) { return node.isTextual() ? node.asText() : null; }

    private void requireEnabled() {
        if (!properties.enabled()) throw new HeapyException(ErrorCode.OCR_UNAVAILABLE);
    }

    @PreDestroy
    void close() { s3.close(); lambda.close(); }
}
