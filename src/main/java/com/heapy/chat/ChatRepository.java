package com.heapy.chat;

import com.heapy.chat.ChatModels.Citation;
import com.heapy.chat.ChatModels.Message;
import com.heapy.chat.ChatModels.Session;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 사용자 소유권을 포함한 상담 조회·저장. @author 김진우 */
@Repository
public class ChatRepository {
    private final JdbcTemplate jdbc;
    public ChatRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockUser(UUID userId) {
        if (jdbc.query("select user_id from public.users where user_id=? and onboarding_completed_at is not null for update",
                (rs, row) -> rs.getObject(1), userId).isEmpty()) {
            throw new HeapyException(ErrorCode.ONBOARDING_INCOMPLETE);
        }
    }

    public Session session(UUID userId, UUID sessionId, boolean lock) {
        return jdbc.query("select * from public.chat_sessions where user_id=? and session_id=?" + (lock ? " for update" : ""),
                this::mapSession, userId, sessionId).stream().findFirst()
                .orElseThrow(() -> new HeapyException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public List<Session> sessions(UUID userId, int limit, int offset) {
        return jdbc.query("""
                select * from public.chat_sessions where user_id=?
                order by last_message_at desc nulls last, created_at desc, session_id desc limit ? offset ?
                """, this::mapSession, userId, limit, offset);
    }

    public void create(UUID userId, UUID sessionId, String companion) {
        jdbc.update("insert into public.chat_sessions(session_id,user_id,title,companion_code) values (?,?,'새 대화',?)",
                sessionId, userId, companion);
    }

    public void delete(UUID userId, UUID sessionId) {
        jdbc.update("delete from public.chat_sessions where user_id=? and session_id=?", userId, sessionId);
        jdbc.update("update private.chat_requests set state='deleted', updated_at=current_timestamp where user_id=? and session_id=?",
                userId, sessionId);
    }

    public String summary(UUID userId, UUID sessionId) {
        return jdbc.queryForObject("select coalesce(summary,'') from public.chat_sessions where user_id=? and session_id=?",
                String.class, userId, sessionId);
    }

    public List<Message> messages(UUID userId, UUID sessionId, int limit, long before) {
        session(userId, sessionId, false);
        List<Message> result = new ArrayList<>(jdbc.query("""
                select m.* from public.chat_messages m join public.chat_sessions s using(session_id)
                where s.user_id=? and m.session_id=? and m.message_order<?
                order by m.message_order desc limit ?
                """, this::mapMessage, userId, sessionId, before, limit));
        Collections.reverse(result);
        return result;
    }

    public Message message(UUID userId, UUID sessionId, UUID messageId) {
        return jdbc.query("""
                select m.* from public.chat_messages m join public.chat_sessions s using(session_id)
                where s.user_id=? and s.session_id=? and m.message_id=?
                """, this::mapMessage, userId, sessionId, messageId).stream().findFirst()
                .orElseThrow(() -> new HeapyException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public long lastOrder(UUID sessionId) {
        Long order = jdbc.queryForObject("select coalesce(max(message_order),0) from public.chat_messages where session_id=?",
                Long.class, sessionId);
        return order == null ? 0 : order;
    }

    public void insertMessage(UUID id, UUID sessionId, String role, String content, long order,
                              String status, String companion) {
        jdbc.update("""
                insert into public.chat_messages(message_id,session_id,role,content,message_order,response_status,companion_code_snapshot)
                values (?,?,?,?,?,?,?)
                """, id, sessionId, role, content, order, status, companion);
    }

    public void insertCitation(UUID messageId, Citation citation) {
        jdbc.update("""
                insert into public.chat_message_citations(citation_id,message_id,display_order,source_type,source_title,source_url,document_id)
                values (?,?,?,'rag',?,?,?)
                """, UUID.randomUUID(), messageId, citation.displayOrder(), citation.sourceTitle(), citation.sourceUrl(), citation.documentId());
    }

    public void updateSummary(UUID sessionId, String summary, boolean completed) {
        jdbc.update("""
                update public.chat_sessions set last_message_at=current_timestamp,updated_at=current_timestamp,
                    summary=case when ? then ? else summary end,
                    summary_version=summary_version+case when ? then 1 else 0 end where session_id=?
                """, completed, summary, completed, sessionId);
    }

    public Optional<RequestRow> request(UUID userId, String scope, UUID key) {
        return jdbc.query("select * from private.chat_requests where user_id=? and scope=? and request_key=?",
                (rs, row) -> new RequestRow(rs.getString("request_hash"), rs.getString("state"),
                        rs.getObject("session_id", UUID.class), rs.getObject("lease_id", UUID.class),
                        rs.getObject("user_message_id", UUID.class), rs.getObject("assistant_message_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant()), userId, scope, key).stream().findFirst();
    }

    public void updateSession(UUID userId, UUID sessionId, String title, String companion) {
        jdbc.update("""
                update public.chat_sessions set title=coalesce(?,title), companion_code=coalesce(?,companion_code),
                    updated_at=current_timestamp where user_id=? and session_id=?
                """, title, companion, userId, sessionId);
    }

    public boolean active(UUID userId, UUID sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from private.chat_requests where user_id=? and session_id=?
                    and state='started' and expires_at>current_timestamp)
                """, Boolean.class, userId, sessionId));
    }

    public void reserve(UUID userId, String scope, UUID key, String hash, UUID sessionId, UUID leaseId, boolean existing) {
        if (existing) {
            jdbc.update("""
                    update private.chat_requests set state='started',lease_id=?,expires_at=current_timestamp+interval '120' second,
                    updated_at=current_timestamp where user_id=? and scope=? and request_key=?
                    """, leaseId, userId, scope, key);
        } else {
            jdbc.update("""
                    insert into private.chat_requests(user_id,scope,request_key,request_hash,state,lease_id,session_id,expires_at)
                    values (?,?,?,?,'started',?,?,current_timestamp+interval '120' second)
                    """, userId, scope, key, hash, leaseId, sessionId);
        }
    }

    public void finish(UUID userId, String scope, UUID key, UUID leaseId, String state, UUID userMessage, UUID assistant) {
        if (jdbc.update("""
                update private.chat_requests set state=?,user_message_id=?,assistant_message_id=?,updated_at=current_timestamp
                where user_id=? and scope=? and request_key=? and lease_id=? and state='started'
                """, state, userMessage, assistant, userId, scope, key, leaseId) != 1) {
            throw new HeapyException(ErrorCode.CHAT_CONFLICT);
        }
    }

    private Session mapSession(ResultSet rs, int row) throws SQLException {
        return new Session(rs.getObject("session_id", UUID.class), rs.getString("title"), rs.getString("companion_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_message_at") == null ? null : rs.getTimestamp("last_message_at").toInstant());
    }

    private Message mapMessage(ResultSet rs, int row) throws SQLException {
        UUID messageId = rs.getObject("message_id", UUID.class);
        List<Citation> citations = jdbc.query("select * from public.chat_message_citations where message_id=? order by display_order",
                (citation, index) -> new Citation(citation.getInt("display_order"), citation.getString("source_title"),
                        citation.getString("source_url"), citation.getString("document_id")), messageId);
        return new Message(messageId, rs.getString("role"), rs.getString("content"), rs.getLong("message_order"),
                rs.getString("response_status"), rs.getTimestamp("created_at").toInstant(), citations, List.of(), rs.getString("companion_code_snapshot"));
    }

    public record RequestRow(String hash, String state, UUID sessionId, UUID leaseId, UUID userMessageId,
                             UUID assistantMessageId, Instant expiresAt) { }
}
