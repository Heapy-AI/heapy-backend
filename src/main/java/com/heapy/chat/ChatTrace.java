package com.heapy.chat;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 원문을 보관하지 않는 요청별 진단 상태. @author 김진우 */
public class ChatTrace {
    public final UUID attemptId = UUID.randomUUID();
    private final long started = System.nanoTime();
    public String stage = "accepted";
    public String errorCode = "none";
    public Integer httpStatus;
    public final Map<String, Object> details = new LinkedHashMap<>();
    private static final Set<String> STAGES = Set.of("accepted", "load_conversation", "load_health_context",
            "internal_request", "prepare_query", "classify_intent", "search_evidence", "generate_answer",
            "verify_answer", "summarize_conversation", "validate_response", "save_conversation", "deliver", "done");
    public void stage(String value) { if (STAGES.contains(value)) stage = value; }
    public long elapsed() { return (System.nanoTime() - started) / 1_000_000L; }
    public void failure(Throwable error) {
        if (!"none".equals(errorCode)) return;
        errorCode = "internal_failure";
        for (int depth = 0; error != null && depth < 10; depth++, error = error.getCause()) {
            if (error instanceof SQLException sql) {
                errorCode = "database_failure";
                if (sql.getSQLState() != null && sql.getSQLState().matches("[A-Z0-9]{5}")) details.put("sqlState", sql.getSQLState());
                return;
            }
            if (error instanceof java.net.SocketTimeoutException) errorCode = "timeout";
            else if (error instanceof java.net.ConnectException) errorCode = "connection_failed";
            else if (error instanceof IllegalArgumentException) errorCode = "invalid_response";
        }
    }
}
