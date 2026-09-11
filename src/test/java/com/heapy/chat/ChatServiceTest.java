package com.heapy.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.heapy.chat.ChatModels.Citation;
import com.heapy.chat.ChatModels.Generated;
import com.heapy.common.exception.HeapyException;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 합성 상담 데이터로 저장·소유권·재시도를 확인한다. @author 김진우 */
class ChatServiceTest {
    private final UUID user = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ChatRepository repository;
    private ChatService service;

    @BeforeEach
    void prepare() {
        source = new HikariDataSource();
        String postgres = System.getenv("HEAPY_CHAT_TEST_POSTGRES_URL");
        boolean localPostgres = postgres != null && postgres.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/heapy_chat_test");
        if (postgres != null && !localPostgres) throw new IllegalArgumentException("합성 전용 로컬 DB만 허용합니다.");
        source.setJdbcUrl(localPostgres ? postgres : "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL");
        source.setUsername(localPostgres ? "postgres" : "sa");
        source.setPassword(localPostgres ? "postgres" : "");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("create schema if not exists private");
        if (localPostgres) {
            jdbc.execute("drop table if exists private.chat_requests, chat_message_citations, chat_messages, chat_sessions, users cascade");
        }
        jdbc.execute("create table users(user_id uuid primary key,onboarding_completed_at timestamp with time zone)");
        jdbc.execute("""
                create table chat_sessions(session_id uuid primary key,user_id uuid references users(user_id),title text default '새 대화',
                companion_code text,summary text,summary_version integer default 0,title_manually_edited boolean not null default false,created_at timestamp with time zone default current_timestamp,
                updated_at timestamp with time zone default current_timestamp,last_message_at timestamp with time zone)
                """);
        jdbc.execute("""
                create table chat_messages(message_id uuid primary key,session_id uuid references chat_sessions(session_id) on delete cascade,
                role text,content text,message_order bigint generated always as identity,response_status text,companion_code_snapshot text,
                created_at timestamp with time zone default current_timestamp,unique(session_id,message_order))
                """);
        jdbc.execute("""
                create table chat_message_citations(citation_id bigint generated always as identity primary key,
                message_id uuid references chat_messages(message_id) on delete cascade,display_order integer,
                source_type text,source_title text,source_url text,document_id text)
                """);
        jdbc.execute("""
                create table private.chat_requests(user_id uuid references public.users(user_id),scope text,request_key uuid,request_hash text,
                state text,lease_id uuid,session_id uuid,user_message_id uuid,assistant_message_id uuid,
                expires_at timestamp with time zone,updated_at timestamp with time zone default current_timestamp,
                primary key(user_id,scope,request_key))
                """);
        jdbc.update("insert into users values (?,current_timestamp),(?,current_timestamp)", user, other);
        repository = new ChatRepository(jdbc);
        service = new ChatService(repository);
    }

    @AfterEach
    void close() { source.close(); }

    @Test
    void 첫질문_제목은_요약이_생길때까지_유지하고_새요약으로_갱신한다() {
        var session = tx(() -> service.create(user, UUID.randomUUID(), "heapy_cat"));
        finishTurn(session.sessionId(), "첫 질문", "");
        assertThat(service.session(user, session.sessionId()).title()).isEqualTo("첫 질문");
        finishTurn(session.sessionId(), "다음 질문", "");
        assertThat(service.session(user, session.sessionId()).title()).isEqualTo("첫 질문");
        finishTurn(session.sessionId(), "세 번째 질문", "활동과 수면에 대한 상담");
        assertThat(service.session(user, session.sessionId()).title()).isEqualTo("활동과 수면에 대한 상담");
        finishTurn(session.sessionId(), "추가 질문", "새 누적 요약");
        assertThat(service.session(user, session.sessionId()).title()).isEqualTo("새 누적 요약");
    }

    @Test
    void 수동제목은_기본제목과_같아도_보호하고_파트너변경은_자동제목을_유지한다() {
        var session = tx(() -> service.create(user, UUID.randomUUID(), "heapy_cat"));
        tx(() -> service.update(user, session.sessionId(), null, "heapy_dog"));
        finishTurn(session.sessionId(), "첫 질문", "");
        assertThat(service.session(user, session.sessionId()).title()).isEqualTo("첫 질문");
        tx(() -> service.update(user, session.sessionId(), "새 대화", null));
        finishTurn(session.sessionId(), "다음 질문", "요약");
        assertThat(service.session(user, session.sessionId()).title()).isEqualTo("새 대화");
    }

    @Test
    void 긴제목은_말줄임하고_원문은_보존한다() {
        var session = tx(() -> service.create(user, UUID.randomUUID(), "heapy_cat"));
        String question = "질".repeat(120);
        finishTurn(session.sessionId(), question, "");
        assertThat(service.session(user, session.sessionId()).title()).isEqualTo("질".repeat(99) + "…");
        assertThat(service.messages(user, session.sessionId(), 20, null).items().getFirst().content()).isEqualTo(question);
        assertThat(ChatService.displayTitle("질".repeat(98) + "😀긴 질문")).isEqualTo("질".repeat(98) + "…");
    }

    private void finishTurn(UUID sessionId, String question, String summary) {
        var reservation = tx(() -> service.reserve(user, sessionId, UUID.randomUUID(), question));
        tx(() -> service.complete(reservation, question, new Generated("합성 답변", "completed", summary, List.of(), Map.of())));
    }

    @Test
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

    @Test
    void 세션_생성_재시도와_다른사용자_격리() {
        UUID key = UUID.randomUUID();
        var first = tx(() -> service.create(user, key, "heapy_cat"));
        assertThat(tx(() -> service.create(user, key, "heapy_cat"))).isEqualTo(first);
        assertThatThrownBy(() -> tx(() -> service.create(user, key, "heapy_dog"))).isInstanceOf(HeapyException.class);
        assertThat(service.sessions(other, 20, null).items()).isEmpty();
        assertThatThrownBy(() -> service.session(other, first.sessionId())).isInstanceOf(HeapyException.class);
    }

    @Test
    void 정상답변_출처_재조회와_재시도는_같은메시지() {
        var session = tx(() -> service.create(user, UUID.randomUUID(), "heapy_cat"));
        UUID key = UUID.randomUUID();
        var reservation = tx(() -> service.reserve(user, session.sessionId(), key, "합성 질문"));
        var generated = new Generated("합성 답변", "completed", "합성 요약",
                List.of(new Citation(1, "합성 근거", "https://example.org/evidence", null)), Map.of());
        var saved = tx(() -> service.complete(reservation, "합성 질문", generated));
        var retry = tx(() -> service.reserve(user, session.sessionId(), key, "합성 질문"));
        assertThat(retry.replay()).isTrue();
        assertThat(service.replay(retry).assistantMessageId()).isEqualTo(saved.assistantMessageId());
        var messages = service.messages(user, session.sessionId(), 20, null).items();
        assertThat(messages).hasSize(2);
        assertThat(messages.getLast().citations()).hasSize(1);
        assertThat(service.context(user, session.sessionId()).summary()).isEqualTo("합성 요약");
    }

    @Test
    void 부분답변은_출처없이_저장하고_기존요약을_보존() {
        var session = tx(() -> service.create(user, UUID.randomUUID(), "heapy_cat"));
        jdbc.update("update chat_sessions set summary='기존 요약' where session_id=?", session.sessionId());
        var reservation = tx(() -> service.reserve(user, session.sessionId(), UUID.randomUUID(), "합성 질문"));
        tx(() -> service.complete(reservation, "합성 질문", new Generated("생성된 부분", "partial", "", List.of(), Map.of())));
        var messages = service.messages(user, session.sessionId(), 20, null).items();
        assertThat(messages.getLast().responseStatus()).isEqualTo("partial");
        assertThat(messages.getLast().citations()).isEmpty();
        assertThat(service.context(user, session.sessionId()).summary()).isEqualTo("기존 요약");
    }

    @Test
    void 생성중_다른질문은_거절하고_실패후_재시도가능() {
        var session = tx(() -> service.create(user, UUID.randomUUID(), "heapy_cat"));
        UUID key = UUID.randomUUID();
        var reservation = tx(() -> service.reserve(user, session.sessionId(), key, "질문"));
        assertThatThrownBy(() -> tx(() -> service.reserve(user, session.sessionId(), UUID.randomUUID(), "다른 질문"))).isInstanceOf(HeapyException.class);
        tx(() -> { service.fail(reservation); return null; });
        assertThat(service.messages(user, session.sessionId(), 20, null).items()).isEmpty();
        assertThat(tx(() -> service.reserve(user, session.sessionId(), key, "질문")).replay()).isFalse();
    }

    @Test
    void 늦게_완료된_이전예약은_새예약을_덮어쓰지_못함() {
        var session = tx(() -> service.create(user, UUID.randomUUID(), "heapy_cat"));
        UUID key = UUID.randomUUID();
        var old = tx(() -> service.reserve(user, session.sessionId(), key, "질문"));
        jdbc.update("update private.chat_requests set expires_at=current_timestamp-interval '1' second where request_key=?", key);
        var fresh = tx(() -> service.reserve(user, session.sessionId(), key, "질문"));
        assertThat(fresh.leaseId()).isNotEqualTo(old.leaseId());
        assertThatThrownBy(() -> tx(() -> service.complete(old, "질문", answer()))).isInstanceOf(HeapyException.class);
        assertThat(service.messages(user, session.sessionId(), 20, null).items()).isEmpty();
    }

    @Test
    void 삭제후_늦은답변과_이전생성요청이_세션을_되살리지_못함() {
        UUID createKey = UUID.randomUUID();
        var session = tx(() -> service.create(user, createKey, "heapy_cat"));
        var reservation = tx(() -> service.reserve(user, session.sessionId(), UUID.randomUUID(), "질문"));
        tx(() -> { service.delete(user, session.sessionId()); return null; });
        assertThatThrownBy(() -> tx(() -> service.complete(reservation, "질문", answer()))).isInstanceOf(HeapyException.class);
        assertThatThrownBy(() -> tx(() -> service.create(user, createKey, "heapy_cat"))).isInstanceOf(HeapyException.class);
        assertThat(service.sessions(user, 20, null).items()).isEmpty();
    }

    @Test
    void 출처저장실패시_질문답변도_롤백() {
        var session = tx(() -> service.create(user, UUID.randomUUID(), "heapy_cat"));
        var reservation = tx(() -> service.reserve(user, session.sessionId(), UUID.randomUUID(), "질문"));
        var failing = spy(repository);
        doThrow(new IllegalStateException("합성 실패")).when(failing).insertCitation(any(), any());
        service = new ChatService(failing);
        var value = new Generated("답변", "completed", "요약", List.of(new Citation(1, "근거", null, "synthetic")), Map.of());
        assertThatThrownBy(() -> tx(() -> service.complete(reservation, "질문", value))).isInstanceOf(IllegalStateException.class);
        assertThat(repository.messages(user, session.sessionId(), 20, Long.MAX_VALUE)).isEmpty();
        assertThat(service.session(user, session.sessionId()).title()).isEqualTo("새 대화");
    }

    private Generated answer() { return new Generated("합성 답변", "completed", "", List.of(), Map.of()); }
    private <T> T tx(Supplier<T> action) { return transaction.execute(status -> action.get()); }
}
