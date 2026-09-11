package com.heapy.medication;

import com.heapy.checkup.OcrJson;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 약·일정·복용 이력을 사용자 단위 트랜잭션으로 관리한다. @author 김진우 */
@Service
@Transactional
public class MedicationService {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public MedicationService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public void lock(UUID user) {
        if (jdbc.query("select user_id from public.users where user_id=? for update",
                (rs, row) -> rs.getObject(1), user).isEmpty()) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    public JsonNode once(UUID user, UUID key, String operation, JsonNode body, Supplier<JsonNode> action) {
        lock(user);
        String hash = OcrJson.hash(operation + ":" + OcrJson.encode(body));
        var previous = jdbc.query("select request_hash,response_body from private.medication_requests where user_id=? and request_key=?",
                (rs, row) -> List.of(rs.getString(1), rs.getString(2)), user, key);
        if (!previous.isEmpty()) {
            if (!hash.equals(previous.getFirst().getFirst())) throw new HeapyException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return OcrJson.MAPPER.readTree(previous.getFirst().get(1));
        }
        JsonNode result = action.get();
        jdbc.update("insert into private.medication_requests(user_id,request_key,request_hash,response_body) values(?,?,?,?)",
                user, key, hash, OcrJson.encode(result));
        return result;
    }

    public JsonNode create(UUID user, UUID key, JsonNode body) {
        MedicationInput.fields(body); MedicationInput.validate(body);
        return once(user, key, "create", body, () -> insert(user, body, null));
    }

    public JsonNode insert(UUID user, JsonNode body, UUID job) {
        MedicationInput.validate(body);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into public.user_medications(medication_id,user_id,display_name,dose_amount,dose_unit,
                dosage_text,instructions,start_date,end_date,status,registration_source,source_ocr_job_id)
                values(?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, user, value(body,"displayName"), amount(body), value(body,"doseUnit"), value(body,"dosageText"),
                value(body,"instructions"), date(body,"startDate"), date(body,"endDate"), state(body),
                job == null ? "manual" : "ocr", job);
        schedules(id, body); generate(user);
        return detail(user, id);
    }

    public JsonNode update(UUID user, UUID id, JsonNode patch) {
        MedicationInput.fields(patch); lock(user);
        ObjectNode body = (ObjectNode) detail(user,id).deepCopy();
        if ("archived".equals(body.path("status").asText())) throw new HeapyException(ErrorCode.MEDICATION_CONFLICT);
        var times = body.putArray("scheduledTimes");
        body.path("schedules").forEach(s -> times.add(s.path("scheduledTime").asText()));
        patch.properties().forEach(e -> body.set(e.getKey(),e.getValue()));
        MedicationInput.validate(body);
        jdbc.update("""
                update public.user_medications set display_name=?,dose_amount=?,dose_unit=?,dosage_text=?,
                instructions=?,start_date=?,end_date=?,status=?,updated_at=? where medication_id=? and user_id=?
                """, value(body,"displayName"),amount(body),value(body,"doseUnit"),value(body,"dosageText"),
                value(body,"instructions"),date(body,"startDate"),date(body,"endDate"),state(body),now(),id,user);
        removeFuture(user,id); schedules(id,body); generate(user);
        return detail(user,id);
    }

    public void archive(UUID user, UUID id) {
        lock(user); detail(user,id);
        removeFuture(user,id);
        jdbc.update("update public.medication_schedules set is_active=false,updated_at=? where medication_id=?",now(),id);
        jdbc.update("update public.user_medications set status='archived',updated_at=? where medication_id=? and user_id=?",now(),id,user);
    }

    private void removeFuture(UUID user, UUID id) {
        jdbc.update("delete from public.medication_intakes where user_id=? and medication_id=? and status='pending' and scheduled_at>?",user,id,now());
    }

    private void schedules(UUID id, JsonNode body) {
        jdbc.update("update public.medication_schedules set is_active=false,updated_at=? where medication_id=?",now(),id);
        for (JsonNode time : body.path("scheduledTimes")) jdbc.update("""
                insert into public.medication_schedules(medication_id,scheduled_time) values(?,?)
                on conflict(medication_id,scheduled_time) do update set is_active=true,updated_at=excluded.updated_at
                """,id,LocalTime.parse(time.asText()));
    }

    private static final String MEDICATION = """
            select jsonb_build_object('medicationId',m.medication_id,'displayName',m.display_name,
            'doseAmount',m.dose_amount,'doseUnit',m.dose_unit,'dosageText',m.dosage_text,'instructions',m.instructions,
            'startDate',m.start_date,'endDate',m.end_date,'status',case when m.status='active' and m.end_date<? then 'completed' else m.status end,
            'registrationSource',m.registration_source,'schedules',coalesce((select jsonb_agg(jsonb_build_object(
            'scheduleId',s.schedule_id,'scheduledTime',s.scheduled_time) order by s.scheduled_time)
            from public.medication_schedules s where s.medication_id=m.medication_id and s.is_active),'[]'::jsonb))
            from public.user_medications m
            """;

    @Transactional(readOnly = true)
    public JsonNode list(UUID user, String status, String cursor, int limit) {
        if (!Set.of("active","completed","archived","all").contains(status)) MedicationInput.invalid();
        UUID after = cursor(cursor); limit(limit);
        var rows = jdbc.query(MEDICATION + """
                where m.user_id=? and (?='all' or (case when m.status='active' and m.end_date<? then 'completed' else m.status end)=?)
                and (?::uuid is null or m.medication_id>?::uuid) order by m.medication_id limit ?
                """, (rs,row) -> OcrJson.MAPPER.readTree(rs.getString(1)),today(),user,status,today(),status,after,after,limit+1);
        return page(rows,limit,"medicationId");
    }

    @Transactional(readOnly = true)
    public JsonNode detail(UUID user, UUID id) {
        ObjectNode detail = (ObjectNode) jdbc.query(MEDICATION + " where m.user_id=? and m.medication_id=?",(rs,row) -> OcrJson.MAPPER.readTree(rs.getString(1)),today(),user,id)
                .stream().findFirst().orElseThrow(() -> new HeapyException(ErrorCode.RESOURCE_NOT_FOUND));
        var history=detail.putArray("recentIntakes");
        jdbc.query(INTAKE+" where user_id=? and medication_id=? and scheduled_at<=? order by scheduled_at desc limit 20",
                (rs,row)->OcrJson.MAPPER.readTree(rs.getString(1)),user,id,now()).forEach(history::add);
        return detail;
    }

    private static final String INTAKE = """
            select jsonb_build_object('intakeId',intake_id,'medicationId',medication_id,'scheduledAt',scheduled_at,
            'displayName',medication_name_snapshot,'dosageText',dosage_snapshot,'status',status,
            'actedAt',acted_at,'missedAt',missed_at,'actionSource',action_source) from public.medication_intakes
            """;

    public JsonNode intakes(UUID user, LocalDate from, LocalDate to, String status, String cursor, int limit) {
        if (from == null || to == null || to.isBefore(from) || to.isAfter(from.plusDays(31))
                || !Set.of("all","pending","taken","skipped","missed").contains(status)) MedicationInput.invalid();
        UUID after = cursor(cursor); limit(limit); lock(user); generate(user);
        var rows = jdbc.query(INTAKE + """
                where user_id=? and scheduled_at>=? and scheduled_at<? and (?='all' or status=?)
                and (?::uuid is null or intake_id>?::uuid) order by intake_id limit ?
                """,(rs,row) -> OcrJson.MAPPER.readTree(rs.getString(1)), user,
                Timestamp.from(from.atStartOfDay(SEOUL).toInstant()),Timestamp.from(to.plusDays(1).atStartOfDay(SEOUL).toInstant()),
                status,status,after,after,limit+1);
        return page(rows,limit,"intakeId");
    }

    public JsonNode act(UUID user, UUID id, UUID key, JsonNode body, String target) {
        if (body == null || !body.isObject() || !Set.of("app","push").contains(body.path("actionSource").asText())
                || (body.hasNonNull("reason") && (!body.path("reason").isString() || body.path("reason").asText().length()>500))) MedicationInput.invalid();
        return once(user,key,target+":"+id,body,() -> {
            var rows=jdbc.query("select status,scheduled_at from public.medication_intakes where user_id=? and intake_id=? for update",
                    (rs,row)->List.of(rs.getString(1),rs.getTimestamp(2).toInstant().toString()),user,id);
            if(rows.isEmpty()) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
            String state=rows.getFirst().getFirst();
            if(Set.of("taken","skipped").contains(state) || Instant.parse(rows.getFirst().get(1)).atZone(SEOUL).toLocalDate().isAfter(today()))
                throw new HeapyException(ErrorCode.MEDICATION_CONFLICT);
            jdbc.update("""
                    update public.medication_intakes set status=?,action_source=?,acted_at=?,idempotency_key=?,updated_at=?
                    where user_id=? and intake_id=?
                    """,target,body.path("actionSource").asText(),now(),key.toString(),now(),user,id);
            return jdbc.queryForObject(INTAKE+" where user_id=? and intake_id=?",(rs,row)->OcrJson.MAPPER.readTree(rs.getString(1)),user,id);
        });
    }

    public void replenish(UUID user) { lock(user); generate(user); }

    private void generate(UUID user) {
        jdbc.update("update public.user_medications set status='completed',updated_at=? where user_id=? and status='active' and end_date<?",now(),user,today());
        jdbc.update("""
                insert into public.medication_intakes(user_id,medication_id,schedule_id,scheduled_at,medication_name_snapshot,dosage_snapshot)
                select m.user_id,m.medication_id,s.schedule_id,(d.day::date+s.scheduled_time) at time zone 'Asia/Seoul',
                m.display_name,concat_ws(' · ',m.dosage_text,nullif(m.instructions,''))
                from public.user_medications m join public.medication_schedules s using(medication_id)
                cross join generate_series(cast(? as date),cast(? as date),interval '1 day') d(day)
                where m.user_id=? and m.status='active' and s.is_active and d.day::date>=m.start_date
                and (m.end_date is null or d.day::date<=m.end_date)
                and (d.day::date+s.scheduled_time) at time zone 'Asia/Seoul'>=?
                on conflict(medication_id,scheduled_at) do nothing
                """,today(),today().plusDays(6),user,now());
        jdbc.update("""
                update public.medication_intakes set status='missed',missed_at=coalesce(missed_at,?),action_source='system',updated_at=?
                where user_id=? and status='pending' and scheduled_at<=?
                """,now(),now(),user,Timestamp.from(clock.instant().minusSeconds(7200)));
    }

    private JsonNode page(List<JsonNode> rows,int limit,String id) {
        ObjectNode result=OcrJson.MAPPER.createObjectNode(); boolean more=rows.size()>limit;
        var items=result.putArray("items"); rows.stream().limit(limit).forEach(items::add);
        result.put("hasNext",more); if(more) result.put("nextCursor",rows.get(limit-1).path(id).asText()); else result.putNull("nextCursor");
        return result;
    }
    private static UUID cursor(String value) {
        try { return value==null?null:UUID.fromString(value); } catch(IllegalArgumentException e) { MedicationInput.invalid(); return null; }
    }
    private static void limit(int limit) { if(limit<1||limit>100) MedicationInput.invalid(); }
    private LocalDate today(){return LocalDate.now(clock.withZone(SEOUL));}
    private Timestamp now(){return Timestamp.from(clock.instant());}
    private String state(JsonNode body){LocalDate end=date(body,"endDate");return end!=null&&end.isBefore(today())?"completed":"active";}
    private static LocalDate date(JsonNode body,String field){return body.hasNonNull(field)?LocalDate.parse(body.path(field).asText()):null;}
    private static String value(JsonNode body,String field){return body.hasNonNull(field)?body.path(field).asText().strip():null;}
    private static Object amount(JsonNode body){return body.hasNonNull("doseAmount")?body.path("doseAmount").decimalValue():null;}
}
