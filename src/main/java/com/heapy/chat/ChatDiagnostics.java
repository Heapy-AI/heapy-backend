package com.heapy.chat;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 진단 실패가 상담 결과에 영향을 주지 않도록 별도 트랜잭션으로 기록한다. @author 김진우 */
@Service
public class ChatDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(ChatDiagnostics.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public ChatDiagnostics(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(3);
    }
    public void record(UUID requestId, UUID sessionId, ChatTrace trace, String status) {
        LOG.info("chat_diagnostic requestId={} attemptId={} status={} stage={} errorCode={} elapsedMs={}",
                requestId, trace.attemptId, status, trace.stage, trace.errorCode, trace.elapsed());
        try {
            String payload = JSON.writeValueAsString(trace.details);
            transaction.executeWithoutResult(ignored -> jdbc.update("""
                insert into private.chat_diagnostics(attempt_id,request_id,session_id,status,stage,error_code,elapsed_ms,http_status,details)
                values (?,?,?,?,?,?,?,?,cast(? as jsonb))
                on conflict (attempt_id) do update set status=excluded.status,stage=excluded.stage,error_code=excluded.error_code,
                elapsed_ms=excluded.elapsed_ms,http_status=excluded.http_status,details=excluded.details,updated_at=now()
                """, trace.attemptId, requestId, sessionId, status, trace.stage, trace.errorCode, trace.elapsed(), trace.httpStatus, payload));
        } catch (Exception ignored) {
            LOG.warn("chat_diagnostic_store_failed requestId={} attemptId={}", requestId, trace.attemptId);
        }
    }

    /** 정식 대화 기록에는 영향을 주지 않고 진단 정보만 정리한다. @author 김진우 */
    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void cleanup() {
        try {
            transaction.executeWithoutResult(ignored -> jdbc.update("""
                delete from private.chat_diagnostics where attempt_id in
                (select attempt_id from private.chat_diagnostics where created_at < now()-interval '14 days' limit 1000)
                """));
        } catch (Exception ignored) { LOG.warn("chat_diagnostic_cleanup_failed"); }
    }
}
