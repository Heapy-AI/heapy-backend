package com.heapy.checkup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로컬 합성 전용 DB에서 실제 마이그레이션을 적용하고 항상 롤백한다.
 * @author 김진우
 */
@EnabledIfEnvironmentVariable(named = "HEAPY_TEST_POSTGRES_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/heapy_test")
class CheckupFindingsMigrationTest {
    private static final Path MIGRATION = Path.of("supabase/migrations/20260908085751_checkup_findings.sql");

    private void database(Consumer<JdbcTemplate> checks) {
        try (var source = new HikariDataSource()) {
            source.setJdbcUrl(System.getenv("HEAPY_TEST_POSTGRES_URL"));
            source.setUsername("postgres");
            source.setPassword("postgres");
            JdbcTemplate jdbc = new JdbcTemplate(source);
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                status.setRollbackOnly();
                jdbc.execute("create schema if not exists private");
                jdbc.execute("create schema if not exists auth");
                jdbc.execute("""
                        do $$ begin
                        if not exists(select 1 from pg_roles where rolname='anon') then create role anon; end if;
                        if not exists(select 1 from pg_roles where rolname='authenticated') then create role authenticated; end if;
                        if not exists(select 1 from pg_roles where rolname='service_role') then create role service_role bypassrls; end if;
                        end $$;
                        """);
                jdbc.execute("""
                        create or replace function auth.uid() returns uuid language sql stable as
                        $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$
                        """);
                jdbc.execute("grant usage on schema public,auth to authenticated");
                jdbc.execute("create table if not exists public.users(user_id uuid primary key)");
                jdbc.execute("""
                        create table if not exists public.health_checkup_records(record_id uuid primary key,
                        user_id uuid references public.users on delete cascade,measured_at date,provider_name text,
                        source_type text,source_ocr_job_id uuid,source_fingerprint text,confirmed_at timestamptz,
                        unique(user_id,source_fingerprint))
                        """);
                jdbc.execute("""
                        create table if not exists public.master_checkup_item(item_code text primary key,is_active boolean,
                        value_type text default 'numeric',item_name text default '합성 검사',display_order smallint default 0)
                        """);
                jdbc.execute("alter table public.master_checkup_item add column if not exists standard_unit text");
                jdbc.execute("alter table public.master_checkup_item add column if not exists item_category text default 'general'");
                jdbc.execute("alter table public.master_checkup_item add column if not exists updated_at timestamptz default now()");
                jdbc.execute("""
                        create table if not exists public.health_checkup_results(result_id uuid primary key,
                        record_id uuid references public.health_checkup_records on delete cascade,
                        item_code text references public.master_checkup_item,value text,numeric_value numeric,unit text,status text,
                        unique(record_id,item_code))
                        """);
                // 작성자: 김진우 — 공유 DB를 허용하지 않는 테스트 전용 트랜잭션에서만 초기화하며 종료 시 복원한다.
                jdbc.execute("drop table if exists public.health_checkup_findings");
                jdbc.execute("delete from public.health_checkup_results where item_code in ('HEARING_GENERAL_LEFT','HEARING_GENERAL_RIGHT','CHEST_XRAY','CHEST_XRAY_PA')");
                jdbc.execute("delete from public.master_checkup_item where item_code in ('HEARING_GENERAL_LEFT','HEARING_GENERAL_RIGHT','CHEST_XRAY','CHEST_XRAY_PA')");
                jdbc.execute("insert into public.master_checkup_item(item_code,item_name,is_active,value_type) values ('CHEST_XRAY_PA','흉부방사선 직접촬영(PA)',true,'numeric')");
                checks.accept(jdbc);
            });
        }
    }

    private void migrate(JdbcTemplate jdbc) {
        try { jdbc.execute(Files.readString(MIGRATION)); }
        catch (IOException exception) { throw new IllegalStateException("마이그레이션 파일을 읽을 수 없습니다.", exception); }
    }

    @Test
    void 실제DDL_JSONB_허용목록_마스터변경을_검증한다() {
        database(jdbc -> {
            migrate(jdbc);
            assertThat(jdbc.queryForObject("select count(*) from public.master_checkup_item where item_code in ('HEARING_GENERAL_LEFT','HEARING_GENERAL_RIGHT','CHEST_XRAY','CHEST_XRAY_PA') and value_type='text' and standard_unit is null and is_active", Integer.class)).isEqualTo(4);
            String valid = OcrJson.encode(OcrReviewValidatorTest.finding());
            assertThat(jdbc.queryForObject("select private.valid_checkup_finding(cast(? as jsonb))", Boolean.class, valid)).isTrue();
            for (String invalid : new String[]{"{}", "[]", "null", valid.replace("procedure_finding", "invalid"),
                    valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""), valid.replace("\"text\":", "\"sourcePath\":")}) {
                assertThat(jdbc.queryForObject("select private.valid_checkup_finding(cast(? as jsonb))", Boolean.class, invalid)).isFalse();
            }
            assertThat(jdbc.queryForObject("select relrowsecurity from pg_class where oid='public.health_checkup_findings'::regclass", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("select has_table_privilege('anon','public.health_checkup_findings','SELECT')", Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("select has_table_privilege('authenticated','public.health_checkup_findings','INSERT')", Boolean.class)).isFalse();
        });
    }

    @Test
    void 소유자RLS와_회차삭제_연쇄삭제를_검증한다() {
        database(jdbc -> {
            migrate(jdbc);
            UUID user = UUID.randomUUID();
            UUID record = UUID.randomUUID();
            jdbc.update("insert into public.users(user_id) values (?)", user);
            jdbc.update("insert into public.health_checkup_records(record_id,user_id) values (?,?)", record, user);
            jdbc.update("insert into public.health_checkup_findings(record_id,finding_id,classification,display_order,content) values (?,?,'procedure_finding',0,cast(? as jsonb))",
                    record, OcrReviewValidatorTest.FINDING_ID, OcrJson.encode(OcrReviewValidatorTest.finding()));
            jdbc.execute("grant select on public.health_checkup_records to authenticated");
            jdbc.execute("set local role authenticated");
            jdbc.queryForObject("select set_config('request.jwt.claim.sub',?,true)", String.class, user.toString());
            assertThat(jdbc.queryForObject("select count(*) from public.health_checkup_findings", Integer.class)).isEqualTo(1);
            jdbc.queryForObject("select set_config('request.jwt.claim.sub',?,true)", String.class, UUID.randomUUID().toString());
            assertThat(jdbc.queryForObject("select count(*) from public.health_checkup_findings", Integer.class)).isZero();
            jdbc.execute("reset role");
            jdbc.update("delete from public.health_checkup_records where record_id=?", record);
            assertThat(jdbc.queryForObject("select count(*) from public.health_checkup_findings", Integer.class)).isZero();
        });
    }

    @Test
    void 기존흉부결과가있으면_마스터변경을_중단한다() {
        database(jdbc -> {
            jdbc.update("insert into public.health_checkup_results(result_id,item_code,value) values (?,'CHEST_XRAY_PA','합성 기존 결과')", UUID.randomUUID());
            assertThatThrownBy(() -> migrate(jdbc)).isInstanceOf(RuntimeException.class);
        });
    }
}
