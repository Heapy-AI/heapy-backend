package com.heapy.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 상담 공개 응답과 내부 전달 모델. @author 김진우 */
public final class ChatModels {
    private ChatModels() { }

    public record CreateRequest(@NotBlank @Pattern(regexp = "heapy_cat|heapy_dog") String companionCode) { }
    public record MessageRequest(@NotBlank @Size(max = 2000) String message) { }
    public record Session(UUID sessionId, String title, String companionCode, Instant createdAt,
                          Instant lastMessageAt) { }
    public record Citation(int displayOrder, String sourceTitle, String sourceUrl, String documentId) { }
    public record Message(UUID messageId, String role, String content, long messageOrder,
                          String responseStatus, Instant createdAt, List<Citation> citations,
                          List<Object> suggestedActions) { }
    public record Page<T>(List<T> items, String nextCursor) { }
    public record Context(Session session, String summary, List<Message> messages) { }
    public record Generated(String answer, String responseStatus, String summary, List<Citation> citations,
                            Map<String, Object> metadata) { }
    public record Reservation(UUID userId, UUID sessionId, UUID key, UUID leaseId, String hash,
                              boolean replay, UUID userMessageId, UUID assistantMessageId) { }
    public record Turn(UUID userMessageId, UUID assistantMessageId, Generated generated) { }
}
