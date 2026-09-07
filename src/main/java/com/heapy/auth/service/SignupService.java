package com.heapy.auth.service;

import com.heapy.auth.client.SupabaseSignupClient;
import com.heapy.auth.client.SupabaseSignupClient.SignupResult;
import com.heapy.auth.dto.SignupRequest;
import com.heapy.auth.dto.SignupResponse;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SignupService {
    private final SupabaseSignupClient client;
    private final JdbcTemplate jdbc;

    public SignupService(SupabaseSignupClient client, JdbcTemplate jdbc) {
        this.client = client;
        this.jdbc = jdbc;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request, UUID idempotencyKey) {
        try {
            String fingerprint = fingerprint(request);
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            jdbc.update("""
                    insert into private.signup_requests (request_key, request_hash)
                    values (?, ?) on conflict (request_key) do nothing
                    """, idempotencyKey, encoder.encode(fingerprint));
            StoredRequest stored = jdbc.queryForObject("""
                    select request_hash, user_id, verification_required from private.signup_requests
                    where request_key = ? for update
                    """, (rs, row) -> new StoredRequest(rs.getString(1), rs.getObject(2, UUID.class), rs.getBoolean(3)),
                    idempotencyKey);
            if (stored == null || !encoder.matches(fingerprint, stored.hash())) {
                throw new HeapyException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            if (stored.userId() != null) {
                return response(stored.userId(), request.email(), stored.verificationRequired());
            }
            SignupResult result = client.signup(request);
            UUID userId = result.userId();
            jdbc.update("""
                    insert into public.users
                        (user_id, chronic_conditions, allergies, onboarding_step, created_at, updated_at)
                    select id, '[]'::jsonb, '[]'::jsonb, 1, now(), now()
                    from auth.users where id = ?
                    on conflict (user_id) do nothing
                    """, userId);
            jdbc.update("update private.signup_requests set user_id = ?, verification_required = ? where request_key = ?",
                    userId, result.emailVerificationRequired(), idempotencyKey);
            return response(userId, request.email(), result.emailVerificationRequired());
        } catch (DataAccessException exception) {
            throw new HeapyException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
        }
    }

    private SignupResponse response(UUID userId, String email, boolean verificationRequired) {
        return new SignupResponse(userId, email, verificationRequired, verificationRequired,
                verificationRequired ? "emailVerification" : "login");
    }

    private String fingerprint(SignupRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((request.email() + "\u0000" + request.password()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("요청 해시를 생성할 수 없습니다.", exception);
        }
    }

    private record StoredRequest(String hash, UUID userId, boolean verificationRequired) { }
}
