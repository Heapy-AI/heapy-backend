package com.heapy.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 합성 자료로 독립 커밋과 진단 장애 격리를 확인한다. @author 김진우 */
class ChatDiagnosticsTest {
    @Test
    void 업무_롤백에도_진단은_남고_진단실패는_전파하지_않는다() {
        String url = System.getenv("HEAPY_CHAT_TEST_POSTGRES_URL");
        Assumptions.assumeTrue(url != null, "PostgreSQL 전용 진단 트랜잭션 검증");
        if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/heapy_chat_test"))
            throw new IllegalArgumentException("합성 전용 로컬 DB만 허용합니다.");
        try (var source = new HikariDataSource()) {
            source.setJdbcUrl(url);
            source.setUsername("postgres");
            source.setPassword("postgres");
            JdbcTemplate jdbc = new JdbcTemplate(source);
            var manager = new DataSourceTransactionManager(source);
            var diagnostics = new ChatDiagnostics(jdbc, manager);
            jdbc.execute("create schema if not exists private");
            jdbc.execute("drop table if exists private.chat_diagnostics");
            jdbc.execute("""
                create table private.chat_diagnostics(attempt_id uuid primary key,request_id uuid,session_id uuid,
                status text,stage text,error_code text,elapsed_ms bigint,http_status integer,details jsonb,updated_at timestamp)
                """);
            var outer = new TransactionTemplate(manager);
            ChatTrace trace = new ChatTrace();
            UUID request = UUID.randomUUID(), session = UUID.randomUUID();
            outer.executeWithoutResult(status -> {
                diagnostics.record(request, session, trace, "failed");
                status.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("select count(*) from private.chat_diagnostics", Integer.class)).isEqualTo(1);
            jdbc.execute("drop table private.chat_diagnostics");
            assertThatCode(() -> diagnostics.record(request, session, trace, "failed")).doesNotThrowAnyException();
        }
    }

    @Test
    void SQL_원문대신_상태코드만_기록한다() {
        ChatTrace trace = new ChatTrace();
        trace.failure(new RuntimeException("synthetic-secret", new SQLException("synthetic-health", "23502")));
        assertThat(trace.errorCode).isEqualTo("database_failure");
        assertThat(trace.details).containsOnlyKeys("sqlState").containsEntry("sqlState", "23502");
    }
}
