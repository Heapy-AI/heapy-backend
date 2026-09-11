package com.heapy.health.repository;

import com.heapy.health.model.HealthData.Record;
import com.heapy.health.model.HealthMetric;
import com.heapy.health.model.HealthPeriod;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 모든 조회를 사용자·측정 범위로 제한한다. @author 김진우 */
@Repository
public class HealthRecordRepository {
    private final JdbcTemplate jdbc;

    public HealthRecordRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public LocalDate latestDate(UUID user, HealthMetric metric, String bioType) {
        List<Object> args = new ArrayList<>();
        args.add(user);
        args.add(metric.dateOnly() ? Date.valueOf(LocalDate.now(HealthPeriod.ZONE)) : Timestamp.from(Instant.now()));
        String filter = "";
        if (bioType != null) { filter = " and bio_type=?"; args.add(bioType); }
        var rows = jdbc.query("select " + metric.time() + " from public." + metric.table()
                        + " where user_id=? and " + metric.time() + "<=?" + filter
                        + " order by " + metric.time() + " desc limit 1",
                (rs, row) -> metric.dateOnly() ? rs.getDate(1).toLocalDate()
                        : rs.getTimestamp(1).toInstant().atZone(HealthPeriod.ZONE).toLocalDate(), args.toArray());
        return rows.isEmpty() ? LocalDate.now(HealthPeriod.ZONE) : rows.getFirst();
    }

    public List<Record> find(UUID user, HealthMetric metric, HealthPeriod period, String bioType,
                             int limit, String afterTime, UUID afterId) {
        List<Object> args = new ArrayList<>();
        args.add(user);
        args.add(metric.dateOnly() ? Date.valueOf(period.from()) : Timestamp.from(period.from().atStartOfDay(HealthPeriod.ZONE).toInstant()));
        args.add(metric.dateOnly() ? Date.valueOf(period.to().plusDays(1)) : Timestamp.from(period.to().plusDays(1).atStartOfDay(HealthPeriod.ZONE).toInstant()));
        String filter = "";
        if (bioType != null) { filter += " and bio_type=?"; args.add(bioType); }
        if (afterId != null) {
            Object time = metric.dateOnly() ? Date.valueOf(afterTime) : Timestamp.from(Instant.parse(afterTime));
            filter += " and (" + metric.time() + " < ? or (" + metric.time() + " = ? and " + metric.id() + " < ?))";
            args.add(time); args.add(time); args.add(afterId);
        }
        args.add(limit);
        String sql = "select * from public." + metric.table() + " where user_id=? and " + metric.time()
                + ">=? and " + metric.time() + "<?" + filter + " order by " + metric.time() + " desc," + metric.id() + " desc limit ?";
        return jdbc.query(sql, (rs, row) -> map(metric, rs), args.toArray());
    }

    private Record map(HealthMetric metric, ResultSet rs) throws SQLException {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 1; index <= rs.getMetaData().getColumnCount(); index++) {
            String key = rs.getMetaData().getColumnLabel(index);
            if (List.of("user_id", "external_record_id", "sync_run_id", "source_updated_at").contains(key)) continue;
            Object value = rs.getObject(index);
            if (value instanceof Timestamp timestamp) value = timestamp.toInstant().toString();
            else if (value instanceof Date date) value = date.toLocalDate().toString();
            else if (value != null && !(value instanceof Number) && !(value instanceof Boolean)) value = value.toString();
            fields.put(key, value);
        }
        Instant measured = metric.dateOnly() ? rs.getDate(metric.time()).toLocalDate().atStartOfDay(HealthPeriod.ZONE).toInstant()
                : rs.getTimestamp(metric.time()).toInstant();
        LocalDate date = measured.atZone(HealthPeriod.ZONE).toLocalDate();
        boolean manual = metric == HealthMetric.WATER && "manual".equals(rs.getString("source"));
        return new Record(rs.getObject(metric.id(), UUID.class), date, measured, rs.getString("source"), manual, manual,
                rs.getTimestamp("updated_at").toInstant().toString(), Collections.unmodifiableMap(fields));
    }
}
