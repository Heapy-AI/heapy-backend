package com.heapy.notification;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사용자에게 표시한 알림만 조회하고 읽음 상태를 계정별로 관리한다. @author 김진우 */
@Service
@Transactional
public class NotificationInboxService {
    public record Item(UUID notificationId, String notificationType, String title, String body, String status,
            String targetType, UUID targetId, Instant scheduledAt, Instant sentAt, Instant openedAt) {}
    public record Items(List<Item> items) {}
    public record Meta(String nextCursor, boolean hasNext, long unreadCount) {}
    public record Page(Items data, Meta meta) {}
    public record Opened(UUID notificationId, String status, Instant openedAt, String targetType, UUID targetId,
            UUID intakeId, Instant scheduledAt, String deepLink) {}
    private record Cursor(Instant date, UUID id) {}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public NotificationInboxService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    private static final String VISIBLE = """
            user_id=? and status in ('sent','opened','actioned') and scheduled_at<=?
            and coalesce(sent_at,scheduled_at)>=? and (purge_after is null or purge_after>?)
            """;
    @Transactional(readOnly=true)
    public Page list(UUID user, String status, String type, String cursor, int limit) {
        if (limit<1 || limit>100 || !Set.of("all","unread","sent","opened","actioned").contains(status)
                || (type != null && !type.matches("[a-z_]{1,60}"))) invalid();
        Cursor after = decode(cursor);
        Instant now = clock.instant();
        List<Item> rows = jdbc.query("select * from public.notifications where " + VISIBLE + """
                and (?='all' or (?='unread' and opened_at is null and status='sent') or status=?)
                and (?::text is null or notification_type=?)
                and (?::timestamptz is null or (scheduled_at,notification_id)<(?::timestamptz,?::uuid))
                order by scheduled_at desc,notification_id desc limit ?
                """, NotificationInboxService::item, user, stamp(now), stamp(now.minusSeconds(30*86400L)), stamp(now),
                status,status,status,type,type,after==null?null:stamp(after.date),after==null?null:stamp(after.date),after==null?null:after.id,limit+1);
        boolean more = rows.size()>limit;
        List<Item> items = List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        String next = more ? encode(items.getLast()) : null;
        Long unread = jdbc.queryForObject("select count(*) from public.notifications where " + VISIBLE + " and status='sent' and opened_at is null",
                Long.class,user,stamp(now),stamp(now.minusSeconds(30*86400L)),stamp(now));
        return new Page(new Items(items),new Meta(next,more,unread==null?0:unread));
    }

    public Opened open(UUID user, UUID id) {
        Instant now = clock.instant();
        // 발송 응답이 아직 기록되지 않은 푸시 탭도 기존 동작대로 처리한다.
        var rows = jdbc.query("""
                select * from public.notifications where notification_id=? and user_id=?
                and (status in ('sent','opened','actioned') or (status in ('failed','scheduled') and failure_code is not null and notification_type='medication_reminder'))
                and scheduled_at<=? and coalesce(sent_at,scheduled_at)>=? and (purge_after is null or purge_after>?) for update
                """,NotificationInboxService::item,id,user,stamp(now),stamp(now.minusSeconds(30*86400L)),stamp(now));
        if(rows.isEmpty()) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
        Item row=rows.getFirst();
        Instant opened=row.openedAt()==null?now:row.openedAt();
        String state="actioned".equals(row.status())?"actioned":"opened";
        jdbc.update("update public.notifications set status=?,opened_at=?,updated_at=?,purge_after=? where user_id=? and notification_id=?",
                state,stamp(opened),stamp(now),stamp(now.plusSeconds(30*86400L)),user,id);
        UUID intake=null;
        if("medication_intake".equals(row.targetType()) && row.targetId()!=null) {
            intake=jdbc.query("select intake_id from public.medication_intakes where intake_id=? and user_id=?",
                    (rs,n)->rs.getObject(1,UUID.class),row.targetId(),user).stream().findFirst().orElse(null);
        }
        return new Opened(id,state,opened,row.targetType(),row.targetId(),intake,row.scheduledAt(),
                intake==null?null:"heapy://medications/intakes/"+intake);
    }

    public int readAll(UUID user) {
        Instant now=clock.instant();
        return jdbc.update("update public.notifications set status='opened',opened_at=?,updated_at=?,purge_after=? where " + VISIBLE
                + " and status='sent' and opened_at is null",stamp(now),stamp(now),stamp(now.plusSeconds(30*86400L)),
                user,stamp(now),stamp(now.minusSeconds(30*86400L)),stamp(now));
    }
    private static Item item(ResultSet rs,int index) throws SQLException {
        return new Item(rs.getObject("notification_id",UUID.class),rs.getString("notification_type"),rs.getString("title"),
                rs.getString("body"),rs.getString("status"),rs.getString("target_type"),rs.getObject("target_id",UUID.class),
                rs.getTimestamp("scheduled_at").toInstant(),instant(rs,"sent_at"),instant(rs,"opened_at"));
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException { Timestamp time=rs.getTimestamp(column); return time==null?null:time.toInstant(); }
    private static Timestamp stamp(Instant time) { return Timestamp.from(time); }
    private static String encode(Item item) { return Base64.getUrlEncoder().withoutPadding().encodeToString((item.scheduledAt()+"|"+item.notificationId()).getBytes(StandardCharsets.UTF_8)); }
    private static Cursor decode(String value) {
        if(value==null) return null;
        try {
            if(value.length()>160) invalid();
            String[] parts=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=2) invalid();
            return new Cursor(Instant.parse(parts[0]),UUID.fromString(parts[1]));
        } catch(RuntimeException exception) { invalid(); return null; }
    }
    private static void invalid() { throw new HeapyException(ErrorCode.INVALID_INPUT); }
}
