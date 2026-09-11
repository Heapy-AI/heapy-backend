package com.heapy.chat;

import com.heapy.chat.ChatModels.CreateRequest;
import com.heapy.chat.ChatModels.MessageRequest;
import com.heapy.chat.ChatModels.Turn;
import com.heapy.chat.ChatModels.UpdateRequest;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.common.response.ApiResponse;
import com.heapy.security.AuthenticatedUser;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 앱은 인증된 Spring 상담 API만 호출한다. @author 김진우 */
@RestController
@Validated
@RequestMapping("/api/chat/sessions")
@ConditionalOnProperty(name = "heapy.chat.enabled", havingValue = "true")
public class ChatController {
    private final ChatService service;
    private final ChatGateway gateway;
    private final ChatHealthContext health;
    private final ChatDiagnostics diagnostics;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(2);

    public ChatController(ChatService service, ChatGateway gateway, ChatHealthContext health, ChatDiagnostics diagnostics) {
        this.service = service; this.gateway = gateway; this.health = health;
        this.diagnostics = diagnostics;
    }

    @PreDestroy
    void close() { executor.shutdownNow(); }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
                                    @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(
                ApiResponse.success(service.create(AuthenticatedUser.id(jwt), key, request.companionCode()), "상담을 시작했습니다."));
    }

    @GetMapping
    public ResponseEntity<?> sessions(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit, @RequestParam(required = false) String cursor) {
        return ok(service.sessions(AuthenticatedUser.id(jwt), limit, cursor));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<?> session(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return ok(service.session(AuthenticatedUser.id(jwt), sessionId));
    }

    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<?> messages(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit, @RequestParam(required = false) String cursor) {
        return ok(service.messages(AuthenticatedUser.id(jwt), sessionId, limit, cursor));
    }

    @PatchMapping("/{sessionId}")
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                                    @Valid @RequestBody UpdateRequest request) {
        return ok(service.update(AuthenticatedUser.id(jwt), sessionId, request.title(), request.companionCode()));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        service.delete(AuthenticatedUser.id(jwt), sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody MessageRequest request) {
        UUID user = AuthenticatedUser.id(jwt);
        if (!capacity.tryAcquire()) throw new HeapyException(ErrorCode.CHAT_CONFLICT);
        ChatModels.Reservation reservation;
        try { reservation = service.reserve(user, sessionId, key, request.message()); }
        catch (RuntimeException exception) { capacity.release(); throw exception; }
        SseEmitter emitter = new SseEmitter(105000L);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));
        executor.submit(() -> {
            ChatTrace trace = new ChatTrace();
            diagnostics.record(key, sessionId, trace, "started");
            try {
                send(emitter, "status", Map.of("stage", "retrieving", "message", "관련 건강 정보를 확인하고 있어요."));
                Turn turn;
                if (reservation.replay()) turn = service.replay(reservation);
                else {
                    trace.stage("load_conversation");
                    var context = service.context(user, sessionId);
                    trace.stage("load_health_context");
                    String personalContext = health.load(user);
                    trace.details.put("personalContextAvailable", !personalContext.isBlank());
                    var generated = gateway.generate(key, request.message(), context, personalContext, stage -> {
                        if (!cancelled.get()) {
                            try { send(emitter, "status", Map.of("stage", stage, "message", "답변을 준비하고 있어요.")); }
                            catch (IOException exception) { cancelled.set(true); }
                        }
                    }, cancelled::get, trace);
                    trace.stage("save_conversation");
                    turn = service.complete(reservation, request.message(), generated);
                }
                trace.stage("deliver");
                if (!cancelled.get()) {
                    send(emitter, "delta", Map.of("content", turn.generated().answer()));
                    send(emitter, "done", Map.of("sessionId", sessionId, "userMessageId", turn.userMessageId(),
                            "assistantMessageId", turn.assistantMessageId(), "responseStatus", turn.generated().responseStatus(),
                            "citations", turn.generated().citations(), "metadata", turn.generated().metadata()));
                }
                trace.stage("done");
                diagnostics.record(key, sessionId, trace, cancelled.get() ? "disconnected" : turn.generated().responseStatus());
                emitter.complete();
            } catch (Exception exception) {
                trace.failure(exception);
                diagnostics.record(key, sessionId, trace, "failed");
                if (!reservation.replay()) {
                    try { service.fail(reservation); } catch (RuntimeException ignored) { }
                }
                try {
                    if (!cancelled.get()) send(emitter, "error", Map.of("code", "CHAT-001",
                            "message", "답변을 완료하지 못했어요. 다시 시도해 주세요.", "traceId", key));
                } catch (IOException ignored) { }
                emitter.complete();
            } finally { capacity.release(); }
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no").body(emitter);
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }
    private ResponseEntity<?> ok(Object value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(value, "상담을 조회했습니다."));
    }
}
