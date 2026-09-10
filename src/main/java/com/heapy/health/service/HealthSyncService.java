package com.heapy.health.service;

import com.heapy.checkup.OcrJson;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthMetric;
import com.heapy.health.service.HealthSyncInput.Record;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 한 배치를 원자적으로 저장하고 원본 버전·직접 입력·재시도를 보존한다. @author 김진우 */
@Service
public class HealthSyncService {
    private final JdbcTemplate jdbc;
    public HealthSyncService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public JsonNode state(UUID user, UUID connection) {
        owned(user, connection);
        Map<String, Object> cursors = new LinkedHashMap<>();
        jdbc.query("select data_type,through_at from private.health_sync_checkpoints where connection_id=?",
                rs -> { cursors.put(rs.getString(1), rs.getTimestamp(2).toInstant().toString()); }, connection);
        return OcrJson.MAPPER.valueToTree(Map.of("connectionId", connection, "cursorState", cursors, "serverTime", Instant.now().toString()));
    }

    @Transactional(readOnly = true)
    public JsonNode run(UUID user, UUID run) {
        var rows = jdbc.queryForList("select r.* from public.health_sync_runs r join public.health_data_connections c using(connection_id) where r.sync_run_id=? and c.user_id=?", run, user);
        if (rows.isEmpty()) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
        var row = rows.getFirst();
        return OcrJson.MAPPER.valueToTree(Map.of("syncRunId", run, "status", row.get("status"), "receivedCount", row.get("received_count"),
                "insertedCount", row.get("inserted_count"), "updatedCount", row.get("updated_count"), "skippedCount", row.get("skipped_count"), "deletedCount", row.get("deleted_count")));
    }

    @Transactional(timeout = 45)
    public JsonNode save(UUID user, UUID key, JsonNode body) {
        if (body == null || !body.isObject() || !body.path("records").isArray() || body.path("records").size() > 500) invalid();
        UUID connection;
        try { connection = UUID.fromString(body.path("connectionId").asText()); } catch (RuntimeException error) { throw new HeapyException(ErrorCode.INVALID_INPUT); }
        String stream = body.path("dataType").asText(""), mode = body.path("syncMode").asText("");
        if (!HealthSyncInput.STREAMS.contains(stream) || !Set.of("app_open", "manual_refresh", "foreground", "background").contains(mode)) invalid();
        Instant through = body.hasNonNull("through") ? HealthSyncInput.instant(body.path("through")) : null;
        List<Record> records = new ArrayList<>();
        body.path("records").forEach(record -> records.add(HealthSyncInput.record(stream, record)));
        if (jdbc.query("select user_id from public.users where user_id=? for update", (rs, i) -> rs.getObject(1), user).isEmpty()) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
        owned(user, connection);
        String hash = OcrJson.hash(OcrJson.encode(body));
        var previous = jdbc.queryForList("select request_hash,response_body::text as body from private.health_record_requests where user_id=? and operation='samsung-sync' and request_key=?", user, key);
        if (!previous.isEmpty()) {
            if (!hash.equals(previous.getFirst().get("request_hash"))) throw new HeapyException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return OcrJson.MAPPER.readTree(previous.getFirst().get("body").toString());
        }
        UUID run = UUID.randomUUID();
        jdbc.update("insert into public.health_sync_runs(sync_run_id,connection_id,idempotency_key,sync_mode,requested_data_types,received_count) values(?,?,?,?,?,?)",
                run, connection, key, mode, new SqlArrayValue("text", stream.equals("activity") ? new Object[]{"steps", "floors", "activity"} : new Object[]{stream}), records.size());
        int[] counts = new int[4];
        for (Record record : records) counts[apply(user, run, record)]++;
        if (through != null) {
            jdbc.update("""
                    insert into private.health_sync_checkpoints(connection_id,data_type,through_at) values(?,?,?)
                    on conflict(connection_id,data_type) do update set through_at=greatest(health_sync_checkpoints.through_at,excluded.through_at)
                    """, connection, stream, Timestamp.from(through));
            // 작성자: 김진우 — 모든 항목이 저장된 시각까지만 전체 동기화 완료로 표시한다.
            jdbc.update("""
                    update public.health_data_connections set last_synced_at=(select min(through_at) from private.health_sync_checkpoints where connection_id=?),updated_at=now()
                    where connection_id=? and (select count(*) from private.health_sync_checkpoints where connection_id=?)=?
                    """, connection, connection, connection, HealthSyncInput.STREAMS.size());
        }
        JsonNode cursors = state(user, connection).path("cursorState");
        jdbc.update("update public.health_sync_runs set status='succeeded',inserted_count=?,updated_count=?,skipped_count=?,deleted_count=?,cursor_state=?::jsonb,completed_at=clock_timestamp() where sync_run_id=?",
                counts[0], counts[1], counts[2], counts[3], OcrJson.encode(cursors), run);
        JsonNode response = OcrJson.MAPPER.valueToTree(Map.of("syncRunId", run, "status", "succeeded", "receivedCount", records.size(),
                "insertedCount", counts[0], "updatedCount", counts[1], "skippedCount", counts[2], "deletedCount", counts[3], "cursorState", cursors));
        jdbc.update("insert into private.health_record_requests(user_id,operation,request_key,request_hash,response_body) values(?,'samsung-sync',?,?,?::jsonb)", user, key, hash, OcrJson.encode(response));
        return response;
    }

    private int apply(UUID user, UUID run, Record record) {
        HealthMetric metric = record.metric();
        var version = jdbc.queryForList("select source_updated_at,deleted from private.health_sync_versions where user_id=? and metric=? and external_record_id=?", user, metric.code(), record.externalId());
        if (!version.isEmpty()) {
            int order = record.updatedAt().compareTo(((Timestamp) version.getFirst().get("source_updated_at")).toInstant());
            if (order < 0 || (order == 0 && (!record.deleted() || Boolean.TRUE.equals(version.getFirst().get("deleted"))))) return 2;
        }
        var rows = jdbc.queryForList("select * from public." + metric.table() + " where user_id=? and external_record_id=? for update", user, record.externalId());
        if (rows.size() > 1) throw new HeapyException(ErrorCode.HEALTH_RECORD_CONFLICT);
        Map<String, Object> row = rows.isEmpty() ? null : rows.getFirst();
        if (row != null && row.get("source_updated_at") instanceof Timestamp old && old.toInstant().isAfter(record.updatedAt())) return 2;
        int result = 2;
        if (record.deleted()) {
            if (row != null) {
                UUID id = (UUID) row.get(metric.id());
                if ("samsung_health".equals(row.get("source"))) {
                    jdbc.update("delete from public." + metric.table() + " where user_id=? and " + metric.id() + "=?", user, id);
                    result = 3;
                } else {
                    if (metric == HealthMetric.WATER) jdbc.update("delete from private.water_record_origins where user_id=? and record_id=?", user, id);
                    update(metric, user, id, nullableMap("external_record_id", null, "source_updated_at", null, "sync_run_id", null, "is_user_override", false));
                }
            }
        } else {
            if (row == null) {
                List<Object> args = new ArrayList<>(List.of(user, record.values().get(metric.time())));
                String filter = metric == HealthMetric.ACTIVITY ? "" : " and source='manual'";
                if (metric == HealthMetric.BIO) { filter += " and bio_type=?"; args.add(record.values().get("bio_type")); }
                var sameTime = jdbc.queryForList("select * from public." + metric.table() + " where user_id=? and " + metric.time() + "=?" + filter + " order by created_at," + metric.id() + " limit 1 for update", args.toArray());
                if (!sameTime.isEmpty()) row = sameTime.getFirst();
            }
            Map<String, Object> values = new LinkedHashMap<>(record.values());
            values.put("external_record_id", record.externalId()); values.put("source_updated_at", Timestamp.from(record.updatedAt())); values.put("sync_run_id", run);
            if (row == null) {
                values.put("user_id", user); values.put(metric.id(), UUID.randomUUID()); values.put("source", "samsung_health");
                jdbc.update("insert into public." + metric.table() + " (" + String.join(",", values.keySet()) + ") values (" + String.join(",", values.keySet().stream().map(k -> "?").toList()) + ")", values.values().toArray());
                result = 0;
            } else if ("samsung_health".equals(row.get("source"))) {
                update(metric, user, (UUID) row.get(metric.id()), values); result = 1;
            } else if ("manual".equals(row.get("source")) && (row.get("external_record_id") == null || record.externalId().equals(row.get("external_record_id")))) {
                UUID id = (UUID) row.get(metric.id());
                if (metric == HealthMetric.WATER) {
                    Map<String, Object> original = new LinkedHashMap<>(values);
                    original.put("consumed_at", ((Timestamp) original.get("consumed_at")).toInstant().toString());
                    original.put("source_updated_at", record.updatedAt().toString());
                    jdbc.update("insert into private.water_record_origins(record_id,user_id,original_values) values(?,?,?::jsonb) on conflict(record_id) do update set original_values=excluded.original_values,updated_at=now()", id, user, OcrJson.encode(original));
                }
                update(metric, user, id, nullableMap("external_record_id", record.externalId(), "source_updated_at", Timestamp.from(record.updatedAt()), "sync_run_id", run, "is_user_override", true));
            }
        }
        jdbc.update("""
                insert into private.health_sync_versions(user_id,metric,external_record_id,source_updated_at,deleted) values(?,?,?,?,?)
                on conflict(user_id,metric,external_record_id) do update set source_updated_at=excluded.source_updated_at,deleted=excluded.deleted
                """, user, metric.code(), record.externalId(), Timestamp.from(record.updatedAt()), record.deleted());
        return result;
    }

    private void owned(UUID user, UUID connection) {
        var valid = jdbc.query("select status,granted_data_types from public.health_data_connections where user_id=? and connection_id=? and provider='samsung_health'",
                (rs, i) -> "connected".equals(rs.getString(1)) && SamsungPermissionPolicy.complete(Arrays.stream((Object[]) rs.getArray(2).getArray()).map(Object::toString).toList()), user, connection);
        if (valid.isEmpty()) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!valid.getFirst()) throw new HeapyException(ErrorCode.HEALTH_SYNC_PERMISSION);
    }
    private void update(HealthMetric metric, UUID user, UUID id, Map<String, Object> values) {
        List<Object> args = new ArrayList<>(values.values()); args.add(user); args.add(id);
        jdbc.update("update public." + metric.table() + " set " + String.join(",", values.keySet().stream().map(k -> k + "=?").toList()) + ",updated_at=clock_timestamp() where user_id=? and " + metric.id() + "=?", args.toArray());
    }
    private static Map<String, Object> nullableMap(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }
    private static void invalid() { throw new HeapyException(ErrorCode.INVALID_INPUT); }
}
