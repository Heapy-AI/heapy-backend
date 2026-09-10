package com.heapy.health.service;

import com.heapy.checkup.OcrJson;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthMetric;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 직접 입력과 출처를 보존하는 물 기록 변경을 직렬화한다. @author 김진우 */
@Service
public class HealthRecordService {
    private final JdbcTemplate jdbc;
    public HealthRecordService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(timeout = 15)
    public JsonNode create(UUID user, String code, UUID key, JsonNode body) {
        HealthMetric metric = HealthMetric.of(code);
        Map<String, Object> values = HealthRecordInput.manual(metric, body);
        return once(user, "create/" + code, key, body, () -> {
            String timeField = metric == HealthMetric.SLEEP ? "start_at" : metric.time();
            List<Object> args = new ArrayList<>(); args.add(user); args.add(values.get(timeField));
            String filter = "";
            if (metric == HealthMetric.BIO) { filter = " and bio_type=?"; args.add(values.get("bio_type")); }
            List<Map<String, Object>> existing = jdbc.queryForList("select * from public." + metric.table()
                    + " where user_id=? and " + timeField + "=?" + filter + " for update", args.toArray());
            if (existing.size() > 1) throw new HeapyException(ErrorCode.HEALTH_RECORD_CONFLICT);
            UUID id;
            if (existing.isEmpty()) {
                id = UUID.randomUUID(); values.put(metric.id(), id); values.put("user_id", user); values.put("source", "manual");
                jdbc.update("insert into public." + metric.table() + " (" + String.join(",", values.keySet()) + ") values ("
                        + String.join(",", values.keySet().stream().map(k -> "?").toList()) + ")", values.values().toArray());
            } else {
                Map<String, Object> row = existing.getFirst(); id = (UUID) row.get(metric.id());
                if (metric == HealthMetric.WATER && "samsung_health".equals(row.get("source"))) backupWater(user, id);
                values.put("source", "manual");
                values.put("is_user_override", row.get("external_record_id") != null);
                update(metric, user, id, values);
            }
            return OcrJson.MAPPER.valueToTree(Map.of("recordId", id, "metric", code, "source", "manual"));
        });
    }

    @Transactional(timeout = 15)
    public JsonNode editWater(UUID user, UUID id, UUID key, JsonNode body) {
        Map<String, Object> values = HealthRecordInput.manual(HealthMetric.WATER, body);
        return once(user, "edit/water/" + id, key, body, () -> {
            Map<String, Object> row = ownedWater(user, id);
            checkVersion(row, body.path("recordVersion").asText(""));
            Timestamp originalTime = (Timestamp) row.get("consumed_at");
            Timestamp newTime = (Timestamp) values.get("consumed_at");
            if (!originalTime.equals(newTime)) {
                Integer count = jdbc.queryForObject("select count(*) from public.lifestyle_water_intake where user_id=? and consumed_at=? and water_intake_id<>?",
                        Integer.class, user, newTime, id);
                if (count != null && count > 0) throw new HeapyException(ErrorCode.HEALTH_RECORD_CONFLICT);
                if (row.get("external_record_id") != null) {
                    restoreWater(user, id);
                    UUID moved = UUID.randomUUID();
                    jdbc.update("insert into public.lifestyle_water_intake(water_intake_id,user_id,consumed_at,amount_ml,source) values(?,?,?,?,'manual')",
                            moved, user, newTime, values.get("amount_ml"));
                    return OcrJson.MAPPER.valueToTree(Map.of("recordId", moved, "restoredRecordId", id));
                }
            }
            update(HealthMetric.WATER, user, id, values);
            return OcrJson.MAPPER.valueToTree(Map.of("recordId", id));
        });
    }

    @Transactional(timeout = 15)
    public JsonNode deleteWater(UUID user, UUID key, JsonNode body) {
        JsonNode records = body == null ? null : body.get("records");
        if (records == null || !records.isArray() || records.isEmpty() || records.size() > 100) throw new HeapyException(ErrorCode.INVALID_INPUT);
        return once(user, "delete/water", key, body, () -> {
            List<UUID> deleted = new ArrayList<>(), restored = new ArrayList<>();
            for (JsonNode entry : records) {
                UUID id;
                try { id = UUID.fromString(entry.path("recordId").asText()); }
                catch (RuntimeException error) { throw new HeapyException(ErrorCode.INVALID_INPUT); }
                if (deleted.contains(id) || restored.contains(id)) throw new HeapyException(ErrorCode.INVALID_INPUT);
                Map<String, Object> row = ownedWater(user, id);
                checkVersion(row, entry.path("recordVersion").asText(""));
                if (row.get("external_record_id") == null) {
                    jdbc.update("delete from public.lifestyle_water_intake where user_id=? and water_intake_id=?", user, id);
                    deleted.add(id);
                } else { restoreWater(user, id); restored.add(id); }
            }
            return OcrJson.MAPPER.valueToTree(Map.of("deletedRecordIds", deleted, "restoredRecordIds", restored));
        });
    }

    private void backupWater(UUID user, UUID id) {
        jdbc.update("""
                insert into private.water_record_origins(record_id,user_id,original_values)
                select water_intake_id,user_id,jsonb_build_object('consumed_at',consumed_at,'amount_ml',amount_ml,
                  'external_record_id',external_record_id,'source_updated_at',source_updated_at,'sync_run_id',sync_run_id)
                from public.lifestyle_water_intake where user_id=? and water_intake_id=?
                on conflict(record_id) do update set original_values=excluded.original_values,updated_at=now()
                """, user, id);
    }

    private void restoreWater(UUID user, UUID id) {
        int restored = jdbc.update("""
                update public.lifestyle_water_intake w set source='samsung_health',is_user_override=false,
                consumed_at=(o.original_values->>'consumed_at')::timestamptz,
                amount_ml=(o.original_values->>'amount_ml')::numeric,updated_at=clock_timestamp()
                from private.water_record_origins o where w.water_intake_id=o.record_id and w.user_id=o.user_id
                and w.user_id=? and w.water_intake_id=?
                """, user, id);
        if (restored != 1) throw new HeapyException(ErrorCode.HEALTH_ORIGIN_REQUIRED);
        jdbc.update("delete from private.water_record_origins where user_id=? and record_id=?", user, id);
    }

    private Map<String, Object> ownedWater(UUID user, UUID id) {
        var rows = jdbc.queryForList("select * from public.lifestyle_water_intake where user_id=? and water_intake_id=? for update", user, id);
        if (rows.isEmpty()) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!"manual".equals(rows.getFirst().get("source"))) throw new HeapyException(ErrorCode.HEALTH_READ_ONLY);
        return rows.getFirst();
    }

    private void checkVersion(Map<String, Object> row, String version) {
        Instant supplied;
        try { supplied = Instant.parse(version); } catch (RuntimeException error) { throw new HeapyException(ErrorCode.INVALID_INPUT); }
        if (!((Timestamp) row.get("updated_at")).toInstant().equals(supplied)) throw new HeapyException(ErrorCode.HEALTH_RECORD_CONFLICT);
    }

    private void update(HealthMetric metric, UUID user, UUID id, Map<String, Object> values) {
        List<Object> args = new ArrayList<>(values.values()); args.add(user); args.add(id);
        jdbc.update("update public." + metric.table() + " set " + String.join(",", values.keySet().stream().map(k -> k + "=?").toList())
                + ",updated_at=clock_timestamp() where user_id=? and " + metric.id() + "=?", args.toArray());
    }

    private JsonNode once(UUID user, String operation, UUID key, JsonNode body, Supplier<JsonNode> work) {
        if (jdbc.query("select user_id from public.users where user_id=? for update", (rs, i) -> rs.getObject(1), user).isEmpty()) {
            throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String hash = OcrJson.hash(OcrJson.encode(body));
        var previous = jdbc.queryForList("select request_hash,response_body::text as body from private.health_record_requests where user_id=? and operation=? and request_key=?", user, operation, key);
        if (!previous.isEmpty()) {
            if (!hash.equals(previous.getFirst().get("request_hash"))) throw new HeapyException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return OcrJson.MAPPER.readTree(previous.getFirst().get("body").toString());
        }
        JsonNode response = work.get();
        jdbc.update("insert into private.health_record_requests(user_id,operation,request_key,request_hash,response_body) values(?,?,?,?,?::jsonb)",
                user, operation, key, hash, OcrJson.encode(response));
        return response;
    }
}
