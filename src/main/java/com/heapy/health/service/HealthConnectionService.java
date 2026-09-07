package com.heapy.health.service;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.dto.HealthConnectionResponse;
import com.heapy.health.dto.SamsungConnectionRequest;
import com.heapy.health.repository.HealthConnectionRepository;
import com.heapy.health.repository.HealthConnectionRepository.StoredRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class HealthConnectionService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HealthConnectionRepository repository;

    public HealthConnectionService(HealthConnectionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<HealthConnectionResponse> findAll(UUID userId) {
        return repository.findAll(userId);
    }

    @Transactional(timeout = 10)
    public SaveResult save(UUID userId, UUID requestKey, SamsungConnectionRequest request) {
        String hash = fingerprint(request);
        if (!repository.lockUser(userId)) {
            throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Optional<StoredRequest> previous = repository.findRequest(userId, requestKey);
        if (previous.isPresent()) {
            StoredRequest stored = previous.get();
            if (!stored.hash().equals(hash)) {
                throw new HeapyException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return new SaveResult(stored.status(), JSON.readValue(stored.body(), HealthConnectionResponse.class));
        }

        Optional<HealthConnectionResponse> existing = repository.find(userId, request.deviceInstallationId());
        String status = request.grantedDataTypes().isEmpty() ? "permission_required" : "connected";
        SamsungConnectionRequest normalized = new SamsungConnectionRequest(request.deviceInstallationId(),
                request.grantedDataTypes().stream().distinct().sorted().toList(),
                request.sdkVersion(), request.permissionCheckedAt());
        if (existing.isEmpty()) {
            repository.insert(userId, normalized, status);
        } else if (existing.get().lastPermissionCheckedAt() == null
                || !request.permissionCheckedAt().isBefore(existing.get().lastPermissionCheckedAt())) {
            // 작성자: 김진우 — 늦게 도착한 과거 권한 확인 결과가 최신 권한 상태를 덮어쓰지 않게 한다.
            repository.update(userId, normalized, status);
        }
        HealthConnectionResponse response = repository.find(userId, request.deviceInstallationId()).orElseThrow();
        int httpStatus = existing.isEmpty() ? 201 : 200;
        repository.saveRequest(userId, requestKey, hash, httpStatus, JSON.writeValueAsString(response));
        return new SaveResult(httpStatus, response);
    }

    private String fingerprint(SamsungConnectionRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(request)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("요청 해시를 생성할 수 없습니다.", exception);
        }
    }

    public record SaveResult(int status, HealthConnectionResponse connection) {
    }
}
