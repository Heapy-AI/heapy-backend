package com.heapy.health.repository;

import com.heapy.health.dto.HealthConnectionResponse;
import com.heapy.health.dto.SamsungConnectionRequest;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
public class HealthConnectionRepository {
    private final JdbcTemplate jdbc;

    public HealthConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lockUser(UUID userId) {
        // 작성자: 김진우 — 같은 사용자의 설치 등록과 멱등성 응답 저장을 하나의 트랜잭션으로 직렬화한다.
        return !jdbc.query("select user_id from public.users where user_id = ? for update",
                (rs, row) -> rs.getObject(1, UUID.class), userId).isEmpty();
    }

    public List<HealthConnectionResponse> findAll(UUID userId) {
        return jdbc.query("""
                select * from public.health_data_connections
                where user_id = ? order by updated_at desc, connection_id
                """, this::mapConnection, userId);
    }

    public Optional<HealthConnectionResponse> find(UUID userId, UUID installationId) {
        return jdbc.query("""
                select * from public.health_data_connections
                where user_id = ? and provider = 'samsung_health' and device_installation_id = ?
                """, this::mapConnection, userId, installationId).stream().findFirst();
    }

    public void insert(UUID userId, SamsungConnectionRequest request, String status) {
        jdbc.update("""
                insert into public.health_data_connections
                    (connection_id, user_id, provider, device_installation_id, status,
                     granted_data_types, sdk_version, last_permission_checked_at, created_at, updated_at)
                values (?, ?, 'samsung_health', ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                """, UUID.randomUUID(), userId, request.deviceInstallationId(), status,
                new SqlArrayValue("text", request.grantedDataTypes().toArray()), request.sdkVersion(),
                Timestamp.from(request.permissionCheckedAt()));
    }

    public void update(UUID userId, SamsungConnectionRequest request, String status) {
        jdbc.update("""
                update public.health_data_connections
                set status = ?, granted_data_types = ?, sdk_version = ?,
                    last_permission_checked_at = ?, updated_at = current_timestamp
                where user_id = ? and provider = 'samsung_health' and device_installation_id = ?
                """, status, new SqlArrayValue("text", request.grantedDataTypes().toArray()),
                request.sdkVersion(), Timestamp.from(request.permissionCheckedAt()), userId,
                request.deviceInstallationId());
    }

    public Optional<StoredRequest> findRequest(UUID userId, UUID requestKey) {
        return jdbc.query("""
                select request_hash, response_status, response_body
                from private.health_connection_requests where user_id = ? and request_key = ?
                """, (rs, row) -> new StoredRequest(rs.getString(1), rs.getInt(2), rs.getString(3)),
                userId, requestKey).stream().findFirst();
    }

    public void saveRequest(UUID userId, UUID requestKey, String hash, int status, String body) {
        jdbc.update("""
                insert into private.health_connection_requests
                    (user_id, request_key, request_hash, response_status, response_body)
                values (?, ?, ?, ?, ?)
                """, userId, requestKey, hash, status, body);
    }

    private HealthConnectionResponse mapConnection(ResultSet rs, int row) throws SQLException {
        Array array = rs.getArray("granted_data_types");
        List<String> permissions;
        try {
            permissions = Arrays.stream((Object[]) array.getArray()).map(Object::toString).toList();
        } finally {
            array.free();
        }
        Timestamp checkedAt = rs.getTimestamp("last_permission_checked_at");
        Timestamp syncedAt = rs.getTimestamp("last_synced_at");
        return new HealthConnectionResponse(rs.getObject("connection_id", UUID.class),
                rs.getString("provider"), rs.getObject("device_installation_id", UUID.class),
                rs.getString("status"), permissions, rs.getString("sdk_version"),
                checkedAt == null ? null : checkedAt.toInstant(), syncedAt == null ? null : syncedAt.toInstant());
    }

    public record StoredRequest(String hash, int status, String body) {
    }
}
