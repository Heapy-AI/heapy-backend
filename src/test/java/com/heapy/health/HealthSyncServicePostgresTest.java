package com.heapy.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.heapy.checkup.OcrJson;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.service.HealthRecordService;
import com.heapy.health.service.HealthSyncService;
import com.heapy.health.service.SamsungPermissionPolicy;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** 로컬 합성 DB에서 실제 JDBC·마이그레이션·원본 복원을 검증하고 전부 롤백한다. @author 김진우 */
@EnabledIfEnvironmentVariable(named = "HEAPY_TEST_POSTGRES_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/heapy_test")
class HealthSyncServicePostgresTest {
    @Test void 동기화_재시도_과거버전_삭제_직접입력복원_소유자를_검증한다() throws Exception {
        try (var source = new HikariDataSource()) {
            source.setJdbcUrl(System.getenv("HEAPY_TEST_POSTGRES_URL")); source.setUsername("postgres"); source.setPassword("postgres");
            JdbcTemplate jdbc = new JdbcTemplate(source);
            String migration = Files.readString(Path.of("supabase/migrations/20260910055554_samsung_health_sync.sql"));
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx -> {
                tx.setRollbackOnly();
                fixture(jdbc); jdbc.execute(migration);
                UUID user = UUID.randomUUID(), connection = UUID.randomUUID(), other = UUID.randomUUID();
                jdbc.update("insert into public.users values (?)", user); jdbc.update("insert into public.users values (?)", other);
                jdbc.update("insert into public.health_data_connections(connection_id,user_id,granted_data_types) values(?,?,?)", connection, user, new SqlArrayValue("text", SamsungPermissionPolicy.REQUIRED.toArray()));
                HealthSyncService sync = new HealthSyncService(jdbc); HealthRecordService manual = new HealthRecordService(jdbc);
                JsonNode first = batch(connection, "2026-08-02T00:00:00Z", 250, false); UUID key = UUID.randomUUID();
                JsonNode result = sync.save(user, key, first);
                assertThat(result.path("insertedCount").asInt()).isEqualTo(1);
                assertThat(sync.save(user, key, first)).isEqualTo(result);
                assertThat(sync.save(user, UUID.randomUUID(), first).path("skippedCount").asInt()).isEqualTo(1);
                assertThatThrownBy(() -> sync.save(other, UUID.randomUUID(), first)).isInstanceOf(HeapyException.class);
                assertThatThrownBy(() -> sync.save(user, key, batch(connection, "2026-08-03T00:00:00Z", 350, false))).isInstanceOf(HeapyException.class);
                manual.create(user, "water", UUID.randomUUID(), OcrJson.MAPPER.readTree("{\"consumedAt\":\"2026-08-01T00:00:00Z\",\"amountMl\":500}"));
                sync.save(user, UUID.randomUUID(), batch(connection, "2026-08-03T00:00:00Z", 350, false));
                assertThat(jdbc.queryForObject("select amount_ml from public.lifestyle_water_intake", Integer.class)).isEqualTo(500);
                assertThat(jdbc.queryForObject("select original_values->>'amount_ml' from private.water_record_origins", String.class)).startsWith("350");
                var row = jdbc.queryForMap("select * from public.lifestyle_water_intake");
                manual.deleteWater(user, UUID.randomUUID(), OcrJson.MAPPER.valueToTree(Map.of("records", new Object[]{Map.of("recordId", row.get("water_intake_id"), "recordVersion", ((Timestamp) row.get("updated_at")).toInstant().toString())})));
                assertThat(jdbc.queryForObject("select amount_ml from public.lifestyle_water_intake", Integer.class)).isEqualTo(350);
                assertThat(jdbc.queryForObject("select source from public.lifestyle_water_intake", String.class)).isEqualTo("samsung_health");
                sync.save(user, UUID.randomUUID(), batch(connection, "2026-08-04T00:00:00Z", 0, true));
                assertThat(jdbc.queryForObject("select count(*) from public.lifestyle_water_intake", Integer.class)).isZero();
                assertThat(sync.save(user, UUID.randomUUID(), first).path("skippedCount").asInt()).isEqualTo(1);
                sync.save(user, UUID.randomUUID(), batch(connection, "2026-08-05T00:00:00Z", 400, false));
                manual.create(user, "water", UUID.randomUUID(), OcrJson.MAPPER.readTree("{\"consumedAt\":\"2026-08-01T00:00:00Z\",\"amountMl\":600}"));
                sync.save(user, UUID.randomUUID(), batch(connection, "2026-08-06T00:00:00Z", 0, true));
                assertThat(jdbc.queryForObject("select amount_ml from public.lifestyle_water_intake", Integer.class)).isEqualTo(600);
                assertThat(jdbc.queryForObject("select external_record_id from public.lifestyle_water_intake", String.class)).isNull();
                assertThat(jdbc.queryForObject("select count(*) from private.water_record_origins", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select last_synced_at from public.health_data_connections", Timestamp.class)).isNull();
                assertThat(jdbc.queryForObject("select has_table_privilege('authenticated','private.health_sync_versions','SELECT')", Boolean.class)).isFalse();
                assertThat(sync.state(user, connection).path("cursorState").path("water").asText()).isEqualTo("2026-08-06T00:00:00Z");
                for (String stream : new String[]{"sleep","heart_rate","blood_glucose","blood_pressure","body_composition","exercise","activity","nutrition"}) {
                    sync.save(user, UUID.randomUUID(), OcrJson.MAPPER.valueToTree(Map.of("connectionId",connection,"syncMode","foreground","dataType",stream,"through","2026-08-07T00:00:00Z","records",new Object[]{})));
                }
                assertThat(jdbc.queryForObject("select last_synced_at from public.health_data_connections", Timestamp.class).toInstant().toString()).isEqualTo("2026-08-06T00:00:00Z");
                jdbc.execute("create table private.health_analysis_invalidations(user_id uuid,analysis_date date,primary key(user_id,analysis_date))");
                jdbc.execute("create trigger invalidate_daily_analysis before update or delete on public.lifestyle_water_intake for each row execute function private.invalidate_health_analysis()");
                jdbc.execute("update public.lifestyle_water_intake set created_at=now()-interval '2 days'");
                jdbc.execute("update public.lifestyle_water_intake set source_updated_at=now(),updated_at=now()");
                assertThat(jdbc.queryForObject("select count(*) from private.health_analysis_invalidations", Integer.class)).isZero();
                jdbc.execute("update public.lifestyle_water_intake set amount_ml=700");
                assertThat(jdbc.queryForObject("select count(*) from private.health_analysis_invalidations", Integer.class)).isEqualTo(1);
            });
        }
    }
    private JsonNode batch(UUID connection, String version, int amount, boolean deleted) {
        return OcrJson.MAPPER.valueToTree(Map.of("connectionId", connection, "syncMode", "manual_refresh", "dataType", "water", "through", version,
                "records", new Object[]{Map.of("metric", "water", "externalRecordId", "water:test", "sourceUpdatedAt", version,
                        "operation", deleted ? "DELETE" : "UPSERT", "data", Map.of("consumedAt", "2026-08-01T00:00:00Z", "amountMl", amount))}));
    }
    private void fixture(JdbcTemplate jdbc) {
        jdbc.execute("create schema if not exists private");
        jdbc.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='anon') then create role anon; end if; if not exists(select 1 from pg_roles where rolname='authenticated') then create role authenticated; end if; if not exists(select 1 from pg_roles where rolname='service_role') then create role service_role; end if; end $$");
        jdbc.execute("create table public.users(user_id uuid primary key)");
        jdbc.execute("create table public.health_data_connections(connection_id uuid primary key,user_id uuid references public.users,status text default 'connected',provider text default 'samsung_health',granted_data_types text[],last_synced_at timestamptz,updated_at timestamptz)");
        jdbc.execute("create table public.health_sync_runs(sync_run_id uuid primary key,connection_id uuid references public.health_data_connections,idempotency_key uuid,sync_mode text,requested_data_types text[],received_count int,status text default 'running',inserted_count int,updated_count int,skipped_count int,cursor_state jsonb,completed_at timestamptz,unique(connection_id,idempotency_key))");
        jdbc.execute("create table public.lifestyle_water_intake(water_intake_id uuid primary key,user_id uuid references public.users,consumed_at timestamptz not null,amount_ml numeric not null check(amount_ml>0),source text,external_record_id text,source_updated_at timestamptz,sync_run_id uuid references public.health_sync_runs,is_user_override boolean default false,created_at timestamptz default now(),updated_at timestamptz default now())");
        jdbc.execute("create table private.water_record_origins(record_id uuid primary key references public.lifestyle_water_intake on delete cascade,user_id uuid,original_values jsonb,updated_at timestamptz default now())");
        jdbc.execute("create table private.health_record_requests(user_id uuid,operation text,request_key uuid,request_hash text,response_body jsonb,primary key(user_id,operation,request_key))");
        jdbc.execute("create table public.lifestyle_activity(user_id uuid,record_date date,steps int not null default 0,floors int not null default 0,active_time_minutes int not null default 0,distance_m numeric not null default 0,active_calories_kcal numeric not null default 0)");
        for (String name : new String[]{"sleep", "bio", "exercise", "nutrition"}) jdbc.execute("create table public.lifestyle_" + name + "(user_id uuid,external_record_id text)");
    }
}
