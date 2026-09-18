package com.heapy.checkup;

import com.heapy.checkup.OcrModels.Confirmation;
import com.heapy.checkup.OcrModels.Confirmed;
import com.heapy.checkup.OcrModels.Correction;
import com.heapy.checkup.OcrModels.Job;
import com.heapy.checkup.OcrModels.Receipt;
import com.heapy.checkup.OcrModels.Snapshot;
import com.heapy.checkup.OcrModels.Detail;
import com.heapy.checkup.OcrModels.DetailResult;
import com.heapy.checkup.OcrModels.Finding;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OcrRepository {
    private final JdbcTemplate jdbc;
    private static final String JOB_SELECT = """
            select j.*, r.source_extension, r.source_size, r.source_hash, r.source_input_type
            from public.ocr_jobs j join private.checkup_ocr_requests r
            on r.job_id = j.job_id and r.operation = 'upload'
            """;

    public OcrRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean lockUser(UUID user) {
        return !jdbc.query("select user_id from public.users where user_id = ? for update",
                (rs, row) -> rs.getObject(1, UUID.class), user).isEmpty();
    }

    public void lockJob(UUID user, UUID job) {
        jdbc.query("select job_id from public.ocr_jobs where user_id = ? and job_id = ? for update",
                (rs, row) -> rs.getObject(1, UUID.class), user, job);
    }

    public Optional<Job> owned(UUID user, UUID job) {
        return owned(user, job, "health_checkup");
    }

    public Optional<Job> owned(UUID user, UUID job, String documentType) {
        return jdbc.query(JOB_SELECT + " where j.user_id = ? and j.job_id = ? and j.document_type = ?",
                this::map, user, job, documentType).stream().findFirst();
    }

    public Optional<Receipt> receipt(UUID user, String operation, UUID key) {
        return jdbc.query("""
                select request_hash, response_body from private.checkup_ocr_requests
                where user_id = ? and operation = ? and request_key = ?
                """, (rs, row) -> new Receipt(rs.getString(1), rs.getString(2)), user, operation, key)
                .stream().findFirst();
    }

    public int activeCount(UUID user) {
        return jdbc.queryForObject("""
                select count(*) from public.ocr_jobs where user_id = ?
                and status in ('pending','processing','review') and expires_at > current_timestamp
                """, Integer.class, user);
    }

    public void insert(Job job, UUID key) {
        jdbc.update("""
                insert into public.ocr_jobs (job_id,user_id,document_type,input_type,status,
                idempotency_key,created_at,updated_at,expires_at)
                values (?,?,?,?,'pending',?,?,?,?)
                """, job.id(), job.userId(), job.documentType(), "camera".equals(job.inputType()) ? "image" : job.inputType(),
                key, Timestamp.from(job.createdAt()), Timestamp.from(job.createdAt()), Timestamp.from(job.expiresAt()));
    }

    public void saveReceipt(Job job, String operation, UUID key, String hash, Object response) {
        boolean upload = "upload".equals(operation);
        jdbc.update("""
                insert into private.checkup_ocr_requests (user_id,operation,request_key,job_id,
                    request_hash,response_body,source_extension,source_size,source_hash,source_input_type)
                values (?,?,?,?,?,?,?,?,?,?)
                """, job.userId(), operation, key, job.id(), hash, OcrJson.encode(response),
                upload ? job.extension() : null, upload ? job.sourceSize() : null,
                upload ? job.sourceHash() : null, upload ? job.inputType() : null);
    }

    public void progress(Job job, Snapshot snapshot, String publicError) {
        String status = switch (snapshot.status()) {
            case "completed" -> "review";
            case "processing" -> "processing";
            case "failed" -> "failed";
            case "expired", "cancelled" -> "expired";
            default -> "pending";
        };
        jdbc.update("""
                update public.ocr_jobs set status = ?, page_count = ?, error_code = ?,
                  started_at = coalesce(started_at, current_timestamp),
                  completed_at = case when ? in ('review','failed','expired') then current_timestamp else null end,
                  updated_at = current_timestamp
                where job_id = ? and status in ('pending','processing') and expires_at > current_timestamp
                """, status, snapshot.pageCount(), publicError, status, job.id());
    }

    public void close(Job job, String state) {
        jdbc.update("update public.ocr_jobs set status = ?, updated_at = current_timestamp where job_id = ?",
                state, job.id());
        jdbc.update("""
                update private.checkup_ocr_requests set cleanup_pending = true
                where job_id = ? and operation = 'upload'
                """, job.id());
    }

    public void cleanupDone(UUID job) {
        jdbc.update("update private.checkup_ocr_requests set cleanup_pending = false where job_id = ?", job);
    }

    public List<Job> pending() {
        return jdbc.query(JOB_SELECT + """
                where j.document_type in ('health_checkup','medication') and j.status in ('pending','processing')
                and j.expires_at > current_timestamp order by j.created_at limit 8
                """, this::map);
    }

    /** 종료된 작업도 외부 파일 정리에서 빠뜨리지 않는다. @author 김진우 */
    public List<Job> forWithdrawal(UUID user) {
        return jdbc.query(JOB_SELECT + " where j.user_id=? order by j.created_at", this::map, user);
    }

    public List<Job> cleanup() {
        return jdbc.query(JOB_SELECT + " where r.cleanup_pending = true and r.cleanup_retry_at <= current_timestamp order by j.updated_at limit 8", this::map);
    }

    public void deferCleanup(UUID job) {
        Integer attempts = jdbc.queryForObject("select cleanup_attempts from private.checkup_ocr_requests where job_id=? and operation='upload'",
                Integer.class, job);
        long delay = Math.min(3600, 30L << Math.min(7, attempts == null ? 0 : attempts));
        jdbc.update("""
                update private.checkup_ocr_requests set cleanup_attempts=cleanup_attempts+1, cleanup_retry_at=?
                where job_id=? and operation='upload'
                """, Timestamp.from(Instant.now().plusSeconds(delay)), job);
    }

    public void expire() {
        jdbc.update("""
                update public.ocr_jobs set status = 'expired', updated_at = current_timestamp
                where document_type in ('health_checkup','medication') and status in ('pending','processing','review')
                and expires_at <= current_timestamp
                """);
    }

    public boolean activeItem(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select count(*) > 0 from public.master_checkup_item where item_code = ? and is_active = true
                """, Boolean.class, code));
    }

    public String itemType(String code) {
        return jdbc.query("select value_type from public.master_checkup_item where item_code = ? and is_active = true",
                (rs, row) -> rs.getString(1), code).stream().findFirst().orElse(null);
    }

    public Optional<Detail> detail(UUID user, UUID record) {
        return jdbc.query("""
                select record_id, measured_at, provider_name from public.health_checkup_records
                where record_id = ? and user_id = ?
                """, (rs, row) -> new Detail(rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(), rs.getString(3),
                        detailResults(record), detailFindings(record, "procedure_finding"), detailFindings(record, "overall_opinion")),
                record, user).stream().findFirst();
    }

    private List<DetailResult> detailResults(UUID record) {
        return jdbc.query("""
                select r.item_code, m.item_name, r.value, r.numeric_value, r.unit, r.status
                from public.health_checkup_results r join public.master_checkup_item m using(item_code)
                where r.record_id = ? order by m.display_order, r.item_code
                """, (rs, row) -> new DetailResult(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getString(5), rs.getString(6)), record);
    }

    private List<Finding> detailFindings(UUID record, String classification) {
        return jdbc.query("""
                select content from public.health_checkup_findings
                where record_id = ? and classification = ? order by display_order, finding_id
                """, (rs, row) -> OcrJson.MAPPER.readValue(rs.getString(1), Finding.class), record, classification);
    }

    public boolean duplicate(UUID user, String fingerprint) {
        return jdbc.queryForObject("""
                select count(*) > 0 from public.health_checkup_records where user_id = ? and source_fingerprint = ?
                """, Boolean.class, user, fingerprint);
    }

    public Confirmed confirm(Job job, Confirmation body, String fingerprint, List<Correction> corrections, Instant now) {
        UUID record = UUID.randomUUID();
        jdbc.update("""
                insert into public.health_checkup_records (record_id,user_id,measured_at,provider_name,
                    source_type,source_ocr_job_id,source_fingerprint,confirmed_at)
                values (?,?,?,?,'ocr',?,?,?)
                """, record, job.userId(), body.measuredAt(), body.providerName(), job.id(), fingerprint, Timestamp.from(now));
        for (var result : body.results()) {
            jdbc.update("""
                    insert into public.health_checkup_results (result_id,record_id,item_code,value,numeric_value,unit,status)
                    values (?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), record, result.itemCode(), result.value(), result.numericValue(), result.unit(), result.status());
        }
        saveFindings(record, body.findings());
        saveFindings(record, body.overallOpinions());
        for (var correction : corrections) {
            jdbc.update("""
                    insert into public.ocr_correction_logs (job_id,item_code,field_key,original_value,corrected_value,correction_type)
                    values (?,?,?,?,?,?)
                    """, job.id(), correction.itemCode(), correction.fieldKey(), correction.originalValue(), correction.correctedValue(),
                    "excluded".equals(correction.correctionType()) ? "remove" : "edit");
        }
        close(job, "confirmed");
        return new Confirmed(record, "ocr", body.results().size(), now, body.findings().size(), body.overallOpinions().size());
    }

    private void saveFindings(UUID record, List<Finding> findings) {
        for (int index = 0; index < findings.size(); index++) {
            Finding finding = findings.get(index);
            jdbc.update("""
                    insert into public.health_checkup_findings(record_id, finding_id, classification, display_order, content)
                    values (?, ?, ?, ?, cast(? as jsonb))
                    """, record, finding.findingId(), finding.classification(), index, OcrJson.encode(finding));
        }
    }

    private Job map(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getObject("job_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("source_input_type"), rs.getString("status"), (Integer) rs.getObject("page_count", Integer.class),
                rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getString("source_extension"), rs.getLong("source_size"), rs.getString("source_hash"), rs.getString("document_type"));
    }
}
