package com.heapy.checkup;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 본인 정식 검진 회차를 원문 없이 페이지 조회한다. @author 김진우 */
@Service
public class CheckupHistoryService {
    private final JdbcTemplate jdbc;

    public CheckupHistoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record RecordSummary(UUID recordId, LocalDate measuredAt, String providerName,
                                String sourceType, long resultCount, Instant confirmedAt) { }
    public record PageMeta(String nextCursor, boolean hasNext, int limit) { }
    public record Page(List<RecordSummary> records, PageMeta meta) { }

    @Transactional(readOnly = true, timeout = 10)
    public Page list(UUID user, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new HeapyException(ErrorCode.INVALID_INPUT);
        List<Object> parameters = new ArrayList<>();
        parameters.add(user);
        String condition = "";
        if (cursor != null) {
            String[] parts = decode(user, cursor);
            UUID record = UUID.fromString(parts[3]);
            if ("null".equals(parts[2])) {
                condition = " and h.measured_at is null and h.record_id < ?";
                parameters.add(record);
            } else {
                Date measured = Date.valueOf(LocalDate.parse(parts[2]));
                condition = " and (h.measured_at < ? or (h.measured_at = ? and h.record_id < ?) or h.measured_at is null)";
                parameters.add(measured); parameters.add(measured); parameters.add(record);
            }
        }
        parameters.add(limit + 1);
        List<RecordSummary> rows = jdbc.query("""
                select h.record_id,h.measured_at,h.provider_name,h.source_type,h.confirmed_at,
                    (select count(*) from public.health_checkup_results r where r.record_id=h.record_id) result_count
                from public.health_checkup_records h where h.user_id=?
                """ + condition + " order by h.measured_at desc nulls last,h.record_id desc limit ?",
                (rs, index) -> new RecordSummary(rs.getObject("record_id", UUID.class),
                        rs.getDate("measured_at") == null ? null : rs.getDate("measured_at").toLocalDate(),
                        rs.getString("provider_name"), rs.getString("source_type"), rs.getLong("result_count"),
                        rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant()),
                parameters.toArray());
        boolean hasNext = rows.size() > limit;
        List<RecordSummary> records = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        String next = hasNext ? encode(user, records.getLast()) : null;
        return new Page(records, new PageMeta(next, hasNext, limit));
    }

    private String encode(UUID user, RecordSummary record) {
        String value = "v1|" + user + "|" + record.measuredAt() + "|" + record.recordId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String[] decode(UUID user, String cursor) {
        try {
            if (cursor.isBlank() || cursor.length() > 256) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 4 || !"v1".equals(parts[0]) || !user.toString().equals(parts[1])
                    || !UUID.fromString(parts[3]).toString().equals(parts[3])) throw new IllegalArgumentException();
            if (!"null".equals(parts[2]) && !LocalDate.parse(parts[2]).toString().equals(parts[2])) throw new IllegalArgumentException();
            return parts;
        } catch (RuntimeException exception) { throw new HeapyException(ErrorCode.INVALID_INPUT); }
    }
}
