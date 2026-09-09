package com.heapy.chat;

import com.heapy.chat.ChatModels.Citation;
import com.heapy.chat.ChatModels.Context;
import com.heapy.chat.ChatModels.Generated;
import com.heapy.chat.ChatModels.Message;
import com.heapy.chat.ChatModels.Page;
import com.heapy.chat.ChatModels.Reservation;
import com.heapy.chat.ChatModels.Session;
import com.heapy.chat.ChatModels.Turn;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 모델 호출 전후의 짧은 트랜잭션과 멱등성을 관리한다. @author 김진우 */
@Service
public class ChatService {
    private final ChatRepository repository;
    public ChatService(ChatRepository repository) { this.repository = repository; }

    @Transactional(timeout = 10)
    public Session create(UUID userId, UUID key, String companion) {
        repository.lockUser(userId);
        String hash = hash(companion);
        var previous = repository.request(userId, "create", key);
        if (previous.isPresent()) {
            validateHash(hash, previous.get().hash());
            if ("deleted".equals(previous.get().state())) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
            Session saved = repository.session(userId, previous.get().sessionId(), false);
            return new Session(saved.sessionId(), "새 대화", companion, saved.createdAt(), null);
        }
        UUID id = UUID.randomUUID();
        UUID lease = UUID.randomUUID();
        repository.reserve(userId, "create", key, hash, id, lease, false);
        repository.create(userId, id, companion);
        repository.finish(userId, "create", key, lease, "completed", null, null);
        return repository.session(userId, id, false);
    }

    @Transactional(readOnly = true)
    public Session session(UUID userId, UUID sessionId) { return repository.session(userId, sessionId, false); }

    @Transactional(readOnly = true)
    public Page<Session> sessions(UUID userId, int limit, String cursor) {
        int offset = (int) decodeCursor(cursor, "sessions", 0, 10000);
        List<Session> rows = repository.sessions(userId, limit + 1, offset);
        return new Page<>(rows.stream().limit(limit).toList(), rows.size() > limit ? cursor("sessions", offset + limit) : null);
    }

    @Transactional(readOnly = true)
    public Page<Message> messages(UUID userId, UUID sessionId, int limit, String cursor) {
        long before = decodeCursor(cursor, sessionId.toString(), Long.MAX_VALUE, Long.MAX_VALUE);
        List<Message> rows = repository.messages(userId, sessionId, limit + 1, before);
        List<Message> items = rows.size() > limit ? rows.subList(1, rows.size()) : rows;
        return new Page<>(items, rows.size() > limit ? cursor(sessionId.toString(), items.getFirst().messageOrder()) : null);
    }

    /** 생성 중인 답변의 파트너가 저장 직전에 바뀌지 않도록 같은 잠금을 사용한다. @author 김진우 */
    @Transactional(timeout = 10)
    public Session update(UUID userId, UUID sessionId, String title, String companion) {
        if ((title == null && companion == null) || (title != null && (title.isBlank() || title.length() > 100))
                || (companion != null && !List.of("heapy_cat", "heapy_dog").contains(companion))) {
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        }
        repository.lockUser(userId);
        repository.session(userId, sessionId, true);
        if (repository.active(userId, sessionId)) throw new HeapyException(ErrorCode.CHAT_CONFLICT);
        repository.updateSession(userId, sessionId, title == null ? null : title.trim(), companion);
        return repository.session(userId, sessionId, false);
    }

    @Transactional(timeout = 10)
    public void delete(UUID userId, UUID sessionId) {
        repository.lockUser(userId);
        repository.session(userId, sessionId, true);
        repository.delete(userId, sessionId);
    }

    @Transactional(timeout = 10)
    public Reservation reserve(UUID userId, UUID sessionId, UUID key, String message) {
        repository.lockUser(userId);
        repository.session(userId, sessionId, true);
        String hash = hash(message);
        String scope = scope(sessionId);
        var previous = repository.request(userId, scope, key);
        if (previous.isPresent()) {
            var row = previous.get();
            validateHash(hash, row.hash());
            if ("deleted".equals(row.state())) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
            if ("completed".equals(row.state())) {
                return new Reservation(userId, sessionId, key, row.leaseId(), hash, true, row.userMessageId(), row.assistantMessageId());
            }
        }
        if (repository.active(userId, sessionId)) throw new HeapyException(ErrorCode.CHAT_CONFLICT);
        UUID lease = UUID.randomUUID();
        repository.reserve(userId, scope, key, hash, sessionId, lease, previous.isPresent());
        return new Reservation(userId, sessionId, key, lease, hash, false, UUID.randomUUID(), UUID.randomUUID());
    }

    @Transactional(readOnly = true)
    public Context context(UUID userId, UUID sessionId) {
        return new Context(repository.session(userId, sessionId, false), repository.summary(userId, sessionId),
                repository.messages(userId, sessionId, 20, Long.MAX_VALUE));
    }

    @Transactional(readOnly = true)
    public Turn replay(Reservation reservation) {
        Message answer = repository.message(reservation.userId(), reservation.sessionId(), reservation.assistantMessageId());
        return new Turn(reservation.userMessageId(), answer.messageId(),
                new Generated(answer.content(), answer.responseStatus(), "", answer.citations(), Map.of("replayed", true)));
    }

    @Transactional(timeout = 10)
    public Turn complete(Reservation reservation, String question, Generated generated) {
        validateGenerated(generated);
        repository.lockUser(reservation.userId());
        Session session = repository.session(reservation.userId(), reservation.sessionId(), true);
        var row = repository.request(reservation.userId(), scope(reservation.sessionId()), reservation.key()).orElseThrow();
        if (!row.leaseId().equals(reservation.leaseId()) || !"started".equals(row.state()) || row.expiresAt().isBefore(Instant.now())) {
            throw new HeapyException(ErrorCode.CHAT_CONFLICT);
        }
        long order = repository.lastOrder(reservation.sessionId());
        repository.insertMessage(reservation.userMessageId(), reservation.sessionId(), "user", question,
                order + 1, "completed", session.companionCode());
        repository.insertMessage(reservation.assistantMessageId(), reservation.sessionId(), "assistant", generated.answer(),
                order + 2, generated.responseStatus(), session.companionCode());
        boolean completed = "completed".equals(generated.responseStatus());
        if (completed) {
            for (Citation citation : generated.citations()) repository.insertCitation(reservation.assistantMessageId(), citation);
        }
        repository.updateSummary(reservation.sessionId(), generated.summary(), completed);
        repository.finish(reservation.userId(), scope(reservation.sessionId()), reservation.key(), reservation.leaseId(),
                "completed", reservation.userMessageId(), reservation.assistantMessageId());
        return new Turn(reservation.userMessageId(), reservation.assistantMessageId(), generated);
    }

    @Transactional(timeout = 10)
    public void fail(Reservation reservation) {
        repository.lockUser(reservation.userId());
        var current = repository.request(reservation.userId(), scope(reservation.sessionId()), reservation.key());
        if (current.isPresent() && "started".equals(current.get().state()) && current.get().leaseId().equals(reservation.leaseId())) {
            repository.finish(reservation.userId(), scope(reservation.sessionId()), reservation.key(), reservation.leaseId(), "failed", null, null);
        }
    }

    private void validateGenerated(Generated value) {
        if (value == null || value.answer() == null || value.answer().isBlank() || value.answer().length() > 16000
                || !List.of("completed", "partial").contains(value.responseStatus()) || value.summary() == null
                || value.summary().length() > 4000 || value.citations() == null || value.citations().size() > 12
                || ("partial".equals(value.responseStatus()) && !value.citations().isEmpty())) {
            throw new HeapyException(ErrorCode.CHAT_UNAVAILABLE);
        }
    }

    private static String scope(UUID id) { return "message:" + id; }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("요청 해시를 만들 수 없습니다."); }
    }
    private void validateHash(String expected, String actual) {
        if (!expected.equals(actual)) throw new HeapyException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
    private String cursor(String scope, long value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((scope + ":" + value).getBytes(StandardCharsets.UTF_8));
    }
    private long decodeCursor(String value, String scope, long fallback, long max) {
        if (value == null || value.isBlank()) return fallback;
        try {
            if (value.length() > 100) throw new IllegalArgumentException();
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            if (!decoded.startsWith(scope + ":")) throw new IllegalArgumentException();
            long result = Long.parseLong(decoded.substring(scope.length() + 1));
            if (result < 0 || result > max) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException exception) { throw new HeapyException(ErrorCode.INVALID_INPUT); }
    }
}
