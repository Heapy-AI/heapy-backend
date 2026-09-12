package com.heapy.notification;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 기존 알림 행을 예약·선점하며 복약 이력은 삭제하지 않는다. @author 김진우 */
@Service
public class MedicationPushService {
    public record Opened(UUID notificationId, UUID intakeId, String scheduledAt, String deepLink) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PushGateway gateway;
    private final Clock clock;
    public MedicationPushService(JdbcTemplate jdbc, PlatformTransactionManager manager, PushGateway gateway, Clock clock) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); this.gateway = gateway; this.clock = clock;
    }
    public void tick() {
        if (!gateway.enabled()) return;
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                update public.notifications set status='scheduled' where notification_type='medication_reminder'
                and status='failed' and failure_code in ('DISPATCH_STARTED:1','DISPATCH_STARTED:2') and updated_at<?
                """, Timestamp.from(clock.instant().minusSeconds(120)));
        jdbc.update("""
                update public.notifications n set status='actioned',actioned_at=i.acted_at,updated_at=?,purge_after=?
                from public.medication_intakes i where n.target_id=i.intake_id and n.user_id=i.user_id
                and n.notification_type='medication_reminder' and n.status in ('sent','opened') and i.status in ('taken','skipped')
                """, now, retention());
        jdbc.update("""
                insert into public.notifications(user_id,notification_type,target_type,target_id,title,body,scheduled_at,idempotency_key)
                select i.user_id,'medication_reminder','medication_intake',i.intake_id,'복약 시간이에요',
                '예정된 약을 확인하고 복용을 기록해 주세요.',i.scheduled_at,'medication:'||i.intake_id
                from public.medication_intakes i join public.user_medications m using(medication_id)
                where i.status='pending' and m.status='active' and i.scheduled_at between ? and ?
                on conflict(user_id,idempotency_key) do nothing
                """, Timestamp.from(clock.instant().minusSeconds(900)), Timestamp.from(clock.instant().plusSeconds(86400)));
        jdbc.update("""
                update public.notifications n set status='cancelled',failure_code='INTAKE_INACTIVE',updated_at=?,purge_after=?
                where n.status='scheduled' and n.notification_type='medication_reminder' and
                (n.scheduled_at<? or not exists(select 1 from public.medication_intakes i
                join public.user_medications m using(medication_id) where i.intake_id=n.target_id
                and i.user_id=n.user_id and i.status='pending' and m.status='active'))
                """, now, retention(), Timestamp.from(clock.instant().minusSeconds(900)));
        var ids = jdbc.query("""
                select notification_id from public.notifications where status='scheduled'
                and notification_type='medication_reminder' and scheduled_at<=?
                and (failure_code is null or updated_at<?) order by scheduled_at limit 100
                """, (rs, row) -> rs.getObject(1, UUID.class), now, Timestamp.from(clock.instant().minusSeconds(120)));
        for (UUID id : ids) deliver(id);
        jdbc.update("delete from public.notifications where purge_after<=? and notification_type='medication_reminder'", now);
    }

    private void deliver(UUID id) {
        // 외부 발송 전 선점을 확정한다. 응답 유실 재시도는 동일 알림 ID를 사용하고 기기에서 중복 표시를 차단한다.
        Map<String,String> payload = transaction.execute(status -> {
            var rows = jdbc.queryForList("""
                    select n.user_id,n.target_id,n.scheduled_at,n.failure_code,d.device_token_id,d.push_token
                    from public.notifications n
                    join public.medication_intakes i on i.intake_id=n.target_id and i.user_id=n.user_id and i.status='pending'
                    and i.scheduled_at=n.scheduled_at
                    join public.user_medications m on m.medication_id=i.medication_id and m.status='active'
                    join lateral(select device_token_id,push_token from private.device_tokens
                    where user_id=n.user_id and is_active and platform='android' order by last_seen_at desc limit 1) d on true
                    where n.notification_id=? and n.status='scheduled' for update of n skip locked
                    """, id);
            if (rows.isEmpty()) return null;
            var row = rows.getFirst();
            int attempt = nextAttempt((String) row.get("failure_code"));
            jdbc.update("update public.notifications set status='failed',failure_code=?,updated_at=?,purge_after=? where notification_id=?",
                    "DISPATCH_STARTED:" + attempt, Timestamp.from(clock.instant()), retention(), id);
            return Map.of("notificationId", id.toString(), "userId", row.get("user_id").toString(),
                    "intakeId", row.get("target_id").toString(), "scheduledAt", ((Timestamp) row.get("scheduled_at")).toInstant().toString(),
                    "deviceTokenId", row.get("device_token_id").toString(), "token", row.get("push_token").toString(), "attempt", String.valueOf(attempt));
        });
        if (payload == null) return;
        PushGateway.Result result = gateway.send(payload.get("token"), Map.of(
                "notificationId", payload.get("notificationId"), "userId", payload.get("userId"),
                "intakeId", payload.get("intakeId"), "scheduledAt", payload.get("scheduledAt"),
                "type", "medication_reminder"));
        if (result.invalidToken()) jdbc.update("""
                update private.device_tokens set is_active=false,invalidated_at=now(),updated_at=now()
                where device_token_id=? and push_token=?
                """, UUID.fromString(payload.get("deviceTokenId")), payload.get("token"));
        boolean retry = !result.sent() && !result.invalidToken() && Integer.parseInt(payload.get("attempt")) < 3
                && !"PUSH_CONFIGURATION_ERROR".equals(result.failure()) && !"PUSH_NOT_CONFIGURED".equals(result.failure());
        jdbc.update("""
                update public.notifications set status=case when status in ('opened','actioned') then status else ? end,
                sent_at=?,provider_message_id=?,failure_code=?,updated_at=?,purge_after=? where notification_id=?
                """, result.sent() ? "sent" : retry ? "scheduled" : "failed", result.sent() ? Timestamp.from(clock.instant()) : null,
                result.messageId(), result.sent() ? null : result.failure() + ":" + payload.get("attempt"), Timestamp.from(clock.instant()), retention(), id);
    }

    public Opened open(UUID user, UUID id) {
        return transaction.execute(status -> {
            var rows = jdbc.query("""
                    select n.target_id,n.scheduled_at from public.notifications n
                    join public.medication_intakes i on i.intake_id=n.target_id and i.user_id=n.user_id
                    where n.notification_id=? and n.user_id=? and n.notification_type='medication_reminder'
                    and n.status in ('sent','opened','actioned','failed') for update of n
                    """, (rs,row) -> new Opened(id, rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant().toString(),
                            "heapy://medications/intakes/" + rs.getObject(1)), id, user);
            if (rows.isEmpty()) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
            jdbc.update("""
                    update public.notifications set status=case when status='actioned' then status else 'opened' end,
                    opened_at=coalesce(opened_at,?),updated_at=?,purge_after=? where notification_id=? and user_id=?
                    """, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), retention(), id, user);
            return rows.getFirst();
        });
    }
    private Timestamp retention() { return Timestamp.from(clock.instant().plusSeconds(30 * 86400L)); }
    private static int nextAttempt(String failure) {
        if (failure == null) return 1;
        try { return Math.min(3, Integer.parseInt(failure.substring(failure.lastIndexOf(':') + 1)) + 1); }
        catch (NumberFormatException exception) { return 3; }
    }
}
