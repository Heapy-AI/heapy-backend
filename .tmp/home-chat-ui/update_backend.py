# 작성자: 김진우 — 기존 스키마의 상담 파트너와 메시지 스냅샷을 연결한다.
from pathlib import Path
root=Path('C:/Users/jinwo/heapy-backend/.worktrees/backend-chat')
base=root/'src/main/java/com/heapy/chat'
p=base/'ChatModels.java';s=p.read_text(encoding='utf-8')
s=s.replace('    public record MessageRequest', '    public record UpdateRequest(@Size(min = 1, max = 100) String title,\n                                @Pattern(regexp = "heapy_cat|heapy_dog") String companionCode) { }\n    public record MessageRequest')
s=s.replace('List<Object> suggestedActions) { }','List<Object> suggestedActions, String companionCodeSnapshot) { }');p.write_text(s,encoding='utf-8')
p=base/'ChatGateway.java';s=p.read_text(encoding='utf-8').replace('"persona", "coach",','"persona", "heapy_dog".equals(context.session().companionCode()) ? "professional" : "coach",');p.write_text(s,encoding='utf-8')
p=base/'ChatRepository.java';s=p.read_text(encoding='utf-8').replace('citations, List.of());','citations, List.of(), rs.getString("companion_code_snapshot"));')
s=s.replace('    public boolean active(', '''    public void updateSession(UUID userId, UUID sessionId, String title, String companion) {
        jdbc.update("""
                update public.chat_sessions set title=coalesce(?,title), companion_code=coalesce(?,companion_code),
                    updated_at=current_timestamp where user_id=? and session_id=?
                """, title, companion, userId, sessionId);
    }

    public boolean active(''');p.write_text(s,encoding='utf-8')
p=base/'ChatService.java';s=p.read_text(encoding='utf-8')
s=s.replace('    @Transactional(timeout = 10)\n    public void delete', '''    /** 생성 중인 답변의 파트너가 저장 직전에 바뀌지 않도록 같은 잠금을 사용한다. @author 김진우 */
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
    public void delete''');p.write_text(s,encoding='utf-8')
p=base/'ChatController.java';s=p.read_text(encoding='utf-8').replace('import com.heapy.chat.ChatModels.Turn;', 'import com.heapy.chat.ChatModels.Turn;\nimport com.heapy.chat.ChatModels.UpdateRequest;').replace('import org.springframework.web.bind.annotation.PathVariable;', 'import org.springframework.web.bind.annotation.PathVariable;\nimport org.springframework.web.bind.annotation.PatchMapping;')
s=s.replace('    @DeleteMapping("/{sessionId}")', '''    @PatchMapping("/{sessionId}")
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                                    @Valid @RequestBody UpdateRequest request) {
        return ok(service.update(AuthenticatedUser.id(jwt), sessionId, request.title(), request.companionCode()));
    }

    @DeleteMapping("/{sessionId}")''');p.write_text(s,encoding='utf-8')
p=root/'src/test/java/com/heapy/chat/ChatGatewayTest.java';s=p.read_text(encoding='utf-8').replace('    private HttpServer server;', '    private final AtomicReference<String> requestBody = new AtomicReference<>();\n    private HttpServer server;').replace('exchange.getRequestBody().readAllBytes();','requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));')
s=s.replace('    private Context context() {', '''    @Test
    void 선택한_파트너를_내부_페르소나로_전달한다() {
        response.set("event: done\\ndata: {\\"answer\\":\\"합성 답변\\",\\"citations\\":[],\\"summary\\":\\"\\",\\"metadata\\":{}}\\n\\n");
        for (String code : List.of("heapy_cat", "heapy_dog")) {
            var context = new Context(new Session(UUID.randomUUID(), "합성", code, Instant.now(), null), "", List.of());
            gateway.generate(UUID.randomUUID(), "질문", context, "", stage -> { }, () -> false);
            assertThat(requestBody.get()).contains("\\"persona\\":\\"" + (code.equals("heapy_cat") ? "coach" : "professional") + "\\"");
        }
    }

    private Context context() {''');p.write_text(s,encoding='utf-8')
p=root/'src/test/java/com/heapy/chat/ChatServiceTest.java';s=p.read_text(encoding='utf-8');pos=s.index('    @Test')
s=s[:pos]+'''    @Test
    void 파트너_변경은_소유권과_생성상태를_검사하고_과거_스냅샷을_보존한다() {
        var session = tx(() -> service.create(user, UUID.randomUUID(), "heapy_cat"));
        var reservation = tx(() -> service.reserve(user, session.sessionId(), UUID.randomUUID(), "합성 질문"));
        assertThatThrownBy(() -> tx(() -> service.update(user, session.sessionId(), null, "heapy_dog"))).isInstanceOf(HeapyException.class);
        tx(() -> service.complete(reservation, "합성 질문", new Generated("합성 답변", "completed", "요약", List.of(), Map.of())));
        assertThatThrownBy(() -> tx(() -> service.update(other, session.sessionId(), null, "heapy_dog"))).isInstanceOf(HeapyException.class);
        assertThatThrownBy(() -> tx(() -> service.update(user, session.sessionId(), null, "invalid"))).isInstanceOf(HeapyException.class);
        var updated = tx(() -> service.update(user, session.sessionId(), "바꾼 제목", "heapy_dog"));
        assertThat(updated.companionCode()).isEqualTo("heapy_dog");
        assertThat(service.context(user, session.sessionId()).summary()).isEqualTo("요약");
        assertThat(service.messages(user, session.sessionId(), 20, null).items()).allMatch(message -> "heapy_cat".equals(message.companionCodeSnapshot()));
        var next = tx(() -> service.reserve(user, session.sessionId(), UUID.randomUUID(), "다음 합성 질문"));
        tx(() -> service.complete(next, "다음 합성 질문", new Generated("다음 합성 답변", "completed", "요약", List.of(), Map.of())));
        assertThat(service.messages(user, session.sessionId(), 20, null).items().getLast().companionCodeSnapshot()).isEqualTo("heapy_dog");
    }

'''+s[pos:];p.write_text(s,encoding='utf-8')
