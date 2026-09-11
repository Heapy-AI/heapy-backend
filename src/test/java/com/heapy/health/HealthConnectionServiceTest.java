package com.heapy.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.heapy.common.exception.HeapyException;
import com.heapy.health.dto.SamsungConnectionRequest;
import com.heapy.health.repository.HealthConnectionRepository;
import com.heapy.health.service.HealthConnectionService;
import com.heapy.health.service.HealthConnectionService.SaveResult;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class HealthConnectionServiceTest {
    private final UUID user = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();
    private final UUID installation = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private HealthConnectionRepository repository;
    private HealthConnectionService service;
    private HikariDataSource source;

    @BeforeEach
    void prepare() {
        String postgres = System.getenv("HEAPY_TEST_POSTGRES_URL");
        source = new HikariDataSource();
        if (postgres != null) {
            if (!postgres.endsWith("/heapy_test")) {
                throw new IllegalStateException("전용 테스트 DB만 사용할 수 있습니다.");
            }
            source.setJdbcUrl(postgres);
            source.setUsername("postgres");
            source.setPassword("postgres");
        } else {
            source.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL");
            source.setUsername("sa");
            source.setPassword("");
        }
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("create schema if not exists private");
        jdbc.execute("create table if not exists public.users (user_id uuid primary key)");
        String arrayType = postgres == null ? "varchar array" : "text[]";
        jdbc.execute("""
                create table if not exists public.health_data_connections (
                    connection_id uuid primary key, user_id uuid references public.users(user_id),
                    provider varchar not null, device_installation_id uuid not null,
                    status varchar not null check (status in ('connected','disconnected','permission_required')),
                    granted_data_types %s not null, sdk_version varchar,
                    last_permission_checked_at timestamp with time zone,
                    last_synced_at timestamp with time zone,
                    created_at timestamp with time zone, updated_at timestamp with time zone,
                    unique(user_id, provider, device_installation_id))
                """.formatted(arrayType));
        jdbc.execute("""
                create table if not exists private.health_connection_requests (
                    user_id uuid references public.users(user_id), request_key uuid,
                    request_hash varchar not null, response_status smallint not null,
                    response_body text not null, primary key(user_id, request_key))
                """);
        jdbc.update("insert into public.users values (?)", user);
        jdbc.update("insert into public.users values (?)", otherUser);
        repository = new HealthConnectionRepository(jdbc);
        service = new HealthConnectionService(repository);
    }

    @AfterEach
    void close() {
        source.close();
    }

    @Test
    void 생성_갱신_부분권한_조회와_사용자격리를_검증한다() {
        SaveResult created = save(user, UUID.randomUUID(), request(List.of("steps"), 0));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.connection().status()).isEqualTo("permission_required");
        assertThat(created.connection().grantedDataTypes()).containsExactly("steps");
        SaveResult updated = save(user, UUID.randomUUID(), request(List.of("steps", "sleep"), 1));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.connection().connectionId()).isEqualTo(created.connection().connectionId());
        assertThat(service.findAll(user)).hasSize(1);
        assertThat(service.findAll(otherUser)).isEmpty();
        assertThat(save(otherUser, UUID.randomUUID(), request(List.of("steps"), 0)).status()).isEqualTo(201);
    }

    @Test
    void 설계의_전체_11개_권한을_저장한다() {
        List<String> types = List.of("sleep", "heart_rate", "blood_glucose", "blood_pressure",
                "body_composition", "exercise", "floors", "steps", "activity", "water", "nutrition");
        SaveResult result = save(user, UUID.randomUUID(), request(types, 0));
        assertThat(result.connection().grantedDataTypes()).containsExactlyInAnyOrderElementsOf(types);
        assertThat(result.connection().status()).isEqualTo("connected");
    }

    @Test
    void 기존_부분권한_연결행은_조회에서_권한필요로_보이고_원본은_유지한다() {
        save(user, UUID.randomUUID(), request(List.of("steps"), 0));
        jdbc.update("update public.health_data_connections set status='connected' where user_id=?", user);
        assertThat(service.findAll(user).getFirst().status()).isEqualTo("permission_required");
        assertThat(repository.findAll(user).getFirst().status()).isEqualTo("connected");
    }

    @Test
    void 하나라도_빠진_권한은_연결완료가_아니다() {
        List<String> types = List.of("sleep", "heart_rate", "blood_glucose", "blood_pressure",
                "body_composition", "exercise", "floors", "steps", "activity", "water");
        assertThat(save(user, UUID.randomUUID(), request(types, 0)).connection().status())
                .isEqualTo("permission_required");
    }

    @Test
    void 권한철회와_과거요청이_최신상태를_덮어쓰지_않는다() {
        save(user, UUID.randomUUID(), request(List.of("steps"), 0));
        SaveResult revoked = save(user, UUID.randomUUID(), request(List.of(), 2));
        assertThat(revoked.connection().status()).isEqualTo("permission_required");
        assertThat(save(user, UUID.randomUUID(), request(List.of("steps"), 1)).connection())
                .isEqualTo(revoked.connection());
    }

    @Test
    void 재시도는_서비스가_재생성돼도_최초응답을_유지하고_다른본문은_거절한다() {
        UUID key = UUID.randomUUID();
        SamsungConnectionRequest initial = request(List.of("steps"), 0);
        SaveResult first = save(user, key, initial);
        save(user, UUID.randomUUID(), request(List.of("sleep"), 1));
        service = new HealthConnectionService(new HealthConnectionRepository(jdbc));
        assertThat(save(user, key, initial)).isEqualTo(first);
        assertThatThrownBy(() -> save(user, key, request(List.of("sleep"), 1)))
                .isInstanceOf(HeapyException.class)
                .satisfies(error -> assertThat(((HeapyException) error).getErrorCode().code())
                        .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(save(otherUser, key, initial).status()).isEqualTo(201);
    }

    @Test
    void 동시_최초등록은_한행만_만들고_재시도는_같은응답을_반환한다() throws Exception {
        UUID key = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(4)) {
            Callable<SaveResult> action = () -> save(user, key, request(List.of("steps"), 0));
            var results = pool.invokeAll(List.of(action, action, action, action));
            SaveResult first = results.getFirst().get();
            for (var result : results) {
                assertThat(result.get()).isEqualTo(first);
            }
        }
        assertThat(service.findAll(user)).hasSize(1);
    }

    @Test
    void 서로다른키의_동시설치등록도_한번만_생성한다() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<SaveResult> action = () -> save(user, UUID.randomUUID(), request(List.of("steps"), 0));
            var results = pool.invokeAll(List.of(action, action));
            assertThat(List.of(results.get(0).get().status(), results.get(1).get().status()))
                    .containsExactlyInAnyOrder(201, 200);
        }
        assertThat(service.findAll(user)).hasSize(1);
    }

    @Test
    void 멱등성저장실패는_연결저장도_롤백한다() {
        HealthConnectionRepository failing = spy(repository);
        doThrow(new IllegalStateException("저장 실패 시험"))
                .when(failing).saveRequest(any(), any(), any(), anyInt(), any());
        service = new HealthConnectionService(failing);
        assertThatThrownBy(() -> save(user, UUID.randomUUID(), request(List.of("steps"), 0)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findAll(user)).isEmpty();
    }

    private SaveResult save(UUID userId, UUID key, SamsungConnectionRequest request) {
        return transaction.execute(status -> service.save(userId, key, request));
    }

    private SamsungConnectionRequest request(List<String> permissions, int seconds) {
        return new SamsungConnectionRequest(installation, permissions, "1.1.0",
                Instant.parse("2026-09-07T10:00:00Z").plusSeconds(seconds));
    }
}
