package com.heapy.notification;

import com.heapy.checkup.OcrJson;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 설치 단위 토큰을 등록하고 계정 전환 시 이전 소유자의 발송을 차단한다. @author 김진우 */
@Service
@Transactional
public class DeviceTokenService {
    public record Registration(@NotBlank @Size(max=200) String deviceIdentifier,
            @Pattern(regexp="android") @NotBlank String platform,
            @NotBlank @Size(max=4096) String pushToken) {}
    public record Registered(UUID deviceTokenId) {}
    private final JdbcTemplate jdbc;
    public DeviceTokenService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Registered register(UUID user, Registration request) {
        // 서로 다른 계정의 동일 토큰 등록도 직렬화한다. 토큰 원문은 잠금 키로 남기지 않는다.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", OcrJson.hash(request.pushToken()));
        if (jdbc.queryForList("select user_id from public.users where user_id=? for update", user).isEmpty())
            throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
        String device = OcrJson.hash(request.deviceIdentifier());
        jdbc.update("delete from private.device_tokens where push_token=? and (user_id<>? or device_identifier_hash<>? or platform<>?)",
                request.pushToken(), user, device, request.platform());
        UUID id = jdbc.queryForObject("""
                insert into private.device_tokens(user_id,device_identifier_hash,platform,push_token)
                values(?,?,?,?) on conflict(user_id,device_identifier_hash,platform) do update
                set push_token=excluded.push_token,is_active=true,invalidated_at=null,last_seen_at=now(),updated_at=now()
                returning device_token_id
                """, UUID.class, user, device, request.platform(), request.pushToken());
        return new Registered(id);
    }

    public void deactivate(UUID user, UUID id) {
        jdbc.update("update private.device_tokens set is_active=false,invalidated_at=now(),updated_at=now() where user_id=? and device_token_id=?", user, id);
    }

    public void deactivateAll(UUID user) {
        jdbc.update("update private.device_tokens set is_active=false,invalidated_at=now(),updated_at=now() where user_id=? and is_active", user);
    }
}
