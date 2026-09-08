package com.heapy.checkup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heapy.checkup.OcrModels.Confirmation;
import com.heapy.checkup.OcrModels.Correction;
import com.heapy.checkup.OcrModels.Job;
import com.heapy.checkup.OcrModels.Result;
import com.heapy.checkup.OcrModels.Snapshot;
import com.heapy.checkup.OcrModels.Confirmed;
import com.heapy.checkup.OcrModels.Finding;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;

class OcrServiceTest {
    private final UUID user = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final AtomicReference<Job> prepared = new AtomicReference<>();
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private OcrRepository repository;
    private OcrGateway gateway;
    private OcrService service;
    private static final String ORIGINAL = """
            {"measuredAt":"2026-08-12","providerName":"합성 검진센터","items":[
            {"fieldKey":"result-1","itemCode":"FASTING_GLUCOSE","itemName":"공복혈당",
             "value":"102","numericValue":102,"unit":"mg/dL","status":"정상","confidence":null}]}
            """;

    @BeforeEach
    void 준비() {
        source = new HikariDataSource();
        String postgres = System.getenv("HEAPY_TEST_POSTGRES_URL");
        if (postgres != null && !postgres.endsWith("/heapy_test")) throw new IllegalStateException("전용 테스트 DB만 허용합니다.");
        source.setJdbcUrl(postgres == null ? "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;NON_KEYWORDS=VALUE" : postgres);
        source.setUsername(postgres == null ? "sa" : "postgres");
        source.setPassword(postgres == null ? "" : "postgres");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create schema if not exists private");
        jdbc.execute("create table if not exists public.users(user_id uuid primary key)");
        jdbc.execute("""
                create table if not exists public.ocr_jobs (
                  job_id uuid primary key,user_id uuid references public.users on delete cascade,
                  document_type text,input_type text check(input_type in ('pdf','image')),
                  status text check(status in ('pending','processing','review','failed','confirmed','expired')),
                  page_count smallint,error_code text,error_message text,idempotency_key uuid,
                  started_at timestamp with time zone,completed_at timestamp with time zone,
                  created_at timestamp with time zone,updated_at timestamp with time zone,expires_at timestamp with time zone,
                  unique(user_id,idempotency_key))
                """);
        jdbc.execute("""
                create table if not exists private.checkup_ocr_requests(
                  user_id uuid references public.users on delete cascade,operation text,request_key uuid,
                  job_id uuid references public.ocr_jobs on delete cascade,request_hash text,response_body text,
                  source_extension text,source_size bigint,source_hash text,source_input_type text,
                  cleanup_pending boolean default false,cleanup_attempts integer default 0,
                  cleanup_retry_at timestamp with time zone default current_timestamp,primary key(user_id,operation,request_key))
                """);
        jdbc.execute("""
                create table if not exists public.master_checkup_item(item_code text primary key,is_active boolean,
                value_type text default 'numeric',item_name text default '합성 검사',display_order smallint default 0)
                """);
        jdbc.execute("""
                create table if not exists public.health_checkup_records(record_id uuid primary key,
                  user_id uuid references public.users on delete cascade,measured_at date,provider_name text,
                  source_type text,source_ocr_job_id uuid,source_fingerprint text,confirmed_at timestamp with time zone,
                  unique(user_id,source_fingerprint))
                """);
        jdbc.execute("""
                create table if not exists public.health_checkup_results(result_id uuid primary key,
                  record_id uuid references public.health_checkup_records on delete cascade,
                  item_code text references public.master_checkup_item,value text,numeric_value numeric,unit text,status text,
                  unique(record_id,item_code))
                """);
        jdbc.execute("""
                create table if not exists public.ocr_correction_logs(job_id uuid references public.ocr_jobs on delete cascade,
                  item_code text references public.master_checkup_item,field_key text,original_value text,corrected_value text,
                  correction_type text check(correction_type in ('edit','add','remove')))
                """);
        jdbc.update("insert into public.users(user_id) values (?),(?)", user, other);
        if (jdbc.queryForObject("select count(*) from public.master_checkup_item where item_code='FASTING_GLUCOSE'", Integer.class) == 0)
            jdbc.update("insert into public.master_checkup_item(item_code,is_active) values ('FASTING_GLUCOSE',true)");
        // 작성자: 김진우 — H2는 JSONB 문자열 바인딩 의미가 달라 저장 무결성 테스트는 PostgreSQL에서 별도 수행한다.
        jdbc.execute("""
                create table if not exists public.health_checkup_findings(
                record_id uuid references public.health_checkup_records on delete cascade,
                finding_id uuid,classification text,display_order smallint,content jsonb,
                primary key(record_id,finding_id))
                """);
        repository = new OcrRepository(jdbc);
        gateway = mock(OcrGateway.class);
        doAnswer(call -> { prepared.set(call.getArgument(0)); return null; }).when(gateway).prepare(any(), any());
        when(gateway.read(any())).thenReturn(new Snapshot("completed", 1, OcrJson.MAPPER.readTree(ORIGINAL), null));
        service = new OcrService(repository, gateway, new DataSourceTransactionManager(source), Clock.systemUTC(),
                new OcrProperties(true, "ap-northeast-2", "577638373354", "fixture", "fixture"));
    }

    @AfterEach
    void 정리() {
        jdbc.update("delete from public.users where user_id in (?,?)", user, other);
        source.close();
    }

    private MockMultipartFile file(String extra) {
        return new MockMultipartFile("file", "합성.pdf", "application/pdf", ("%PDF-1.4 " + extra).getBytes(StandardCharsets.UTF_8));
    }

    private Confirmation edited() {
        return new Confirmation(LocalDate.of(2026, 8, 12), "합성 검진센터",
                List.of(new Result("FASTING_GLUCOSE", "100", new BigDecimal("100"), "mg/dL", "정상")),
                List.of(new Correction("result-1.value", "FASTING_GLUCOSE", "102", "100", "value")));
    }

    private void expectError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(HeapyException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    @Test
    void 촬영_입력은_기존DB값과_내부요청값을_구분한다() {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0};
        UUID job = service.upload(user, UUID.randomUUID(), "camera", new MockMultipartFile("file", jpeg)).jobId();
        assertThat(prepared.get().inputType()).isEqualTo("camera");
        assertThat(repository.owned(user, job).orElseThrow().inputType()).isEqualTo("camera");
        assertThat(jdbc.queryForObject("select input_type from public.ocr_jobs where job_id=?", String.class, job)).isEqualTo("image");
    }

    @Test
    void 같은_업로드는_재사용하고_다른_파일로_같은키를_쓰면_거절한다() {
        UUID key = UUID.randomUUID();
        var first = service.upload(user, key, "pdf", file("a"));
        assertThat(OcrJson.encode(service.upload(user, key, "pdf", file("a")))).isEqualTo(OcrJson.encode(first));
        expectError(() -> service.upload(user, key, "pdf", file("b")), ErrorCode.IDEMPOTENCY_KEY_REUSED);
        verify(gateway, times(1)).prepare(any(), any());
        assertThat(prepared.get().expiresAt()).isEqualTo(prepared.get().createdAt().plusSeconds(600));
    }

    @Test
    void 다른사용자는_조회_확정_취소할수없다() {
        UUID job = service.upload(user, UUID.randomUUID(), "pdf", file("a")).jobId();
        expectError(() -> service.get(other, job), ErrorCode.RESOURCE_NOT_FOUND);
        expectError(() -> service.confirm(other, job, UUID.randomUUID(), edited()), ErrorCode.RESOURCE_NOT_FOUND);
        expectError(() -> service.cancel(other, job), ErrorCode.RESOURCE_NOT_FOUND);
        verify(gateway, times(0)).read(any());
    }

    @Test
    void 완료상태를_변환하고_확정값과_실제교정만_저장한다() {
        UUID job = service.upload(user, UUID.randomUUID(), "pdf", file("a")).jobId();
        assertThat(service.get(user, job).status()).isEqualTo("completed");
        assertThat(repository.owned(user, job).orElseThrow().status()).isEqualTo("review");
        var saved = service.confirm(user, job, UUID.randomUUID(), edited());
        assertThat(saved.resultCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select value from public.health_checkup_results where record_id=?", String.class, saved.recordId())).isEqualTo("100");
        assertThat(jdbc.queryForObject("select correction_type from public.ocr_correction_logs where job_id=?", String.class, job)).isEqualTo("edit");
        assertThat(repository.owned(user, job).orElseThrow().status()).isEqualTo("confirmed");
        expectError(() -> service.get(user, job), ErrorCode.OCR_EXPIRED);
        verify(gateway).purge(any(), anyString());
    }

    @Test
    void 정리장애후에도_확정재시도는_최초응답을_반환한다() {
        UUID job = service.upload(user, UUID.randomUUID(), "pdf", file("a")).jobId();
        UUID key = UUID.randomUUID();
        doThrow(new HeapyException(ErrorCode.OCR_UNAVAILABLE)).when(gateway).purge(any(), anyString());
        var first = service.confirm(user, job, key, edited());
        jdbc.update("update public.ocr_jobs set expires_at=? where job_id=?", Timestamp.from(Instant.now().minusSeconds(1)), job);
        assertThat(service.confirm(user, job, key, edited())).isEqualTo(first);
        assertThat(jdbc.queryForObject("select cleanup_pending from private.checkup_ocr_requests where job_id=? and operation='upload'", Boolean.class, job)).isTrue();
        verify(gateway, times(1)).read(any());
    }

    @Test
    void 거짓_교정과_수치불일치는_저장하지_않는다() {
        UUID job = service.upload(user, UUID.randomUUID(), "pdf", file("a")).jobId();
        Confirmation forged = new Confirmation(edited().measuredAt(), edited().providerName(), edited().results(), List.of());
        expectError(() -> service.confirm(user, job, UUID.randomUUID(), forged), ErrorCode.OCR_INVALID_RESULT);
        Confirmation inconsistent = new Confirmation(edited().measuredAt(), edited().providerName(),
                List.of(new Result("FASTING_GLUCOSE", "100", new BigDecimal("200"), "mg/dL", "정상")), edited().corrections());
        expectError(() -> service.confirm(user, job, UUID.randomUUID(), inconsistent), ErrorCode.OCR_INVALID_RESULT);
        assertThat(jdbc.queryForObject("select count(*) from public.health_checkup_records where user_id=?", Integer.class, user)).isZero();
    }

    @Test
    void 동시에_확정해도_한번만_저장한다() throws Exception {
        UUID job = service.upload(user, UUID.randomUUID(), "pdf", file("a")).jobId();
        UUID key = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.confirm(user, job, key, edited()));
            var second = pool.submit(() -> service.confirm(user, job, key, edited()));
            assertThat(first.get()).isEqualTo(second.get());
        }
        assertThat(jdbc.queryForObject("select count(*) from public.health_checkup_records where user_id=?", Integer.class, user)).isEqualTo(1);
    }

    @Test
    void 만료와_화면이탈_후에는_결과를_반환하지_않는다() {
        UUID job = service.upload(user, UUID.randomUUID(), "pdf", file("a")).jobId();
        service.cancel(user, job);
        expectError(() -> service.get(user, job), ErrorCode.OCR_EXPIRED);
        expectError(() -> service.confirm(user, job, UUID.randomUUID(), edited()), ErrorCode.OCR_EXPIRED);
        verify(gateway, times(0)).read(any());
    }

    @Test
    void 잘못된_파일_형식과_업로드_한도를_거절한다() {
        expectError(() -> service.upload(user, UUID.randomUUID(), "image", file("a")), ErrorCode.OCR_UNSUPPORTED_FILE);
        expectError(() -> service.upload(user, UUID.randomUUID(), "pdf",
                new MockMultipartFile("file", new byte[20_000_001])), ErrorCode.OCR_FILE_LIMIT);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HEAPY_TEST_POSTGRES_URL", matches = ".+")
    void 소견전용확정은_정리후에도_상세에남고_다른사용자는_조회할수없다() {
        when(gateway.read(any())).thenReturn(new Snapshot("completed", 1, OcrReviewValidatorTest.snapshot(), null));
        UUID job = service.upload(user, UUID.randomUUID(), "pdf", file("소견 전용")).jobId();
        Confirmation body = OcrReviewValidatorTest.body(List.of(), List.of(OcrReviewValidatorTest.finding()), List.of(), List.of());
        assertThat(service.get(user, job).result().path("findings").size()).isEqualTo(1);
        UUID key = UUID.randomUUID();
        var saved = service.confirm(user, job, key, body);
        assertThat(saved.resultCount()).isZero();
        assertThat(saved.findingCount()).isEqualTo(1);
        jdbc.update("update public.ocr_jobs set expires_at=? where job_id=?", Timestamp.from(Instant.now().minusSeconds(1)), job);
        assertThat(repository.detail(user, saved.recordId()).orElseThrow().findings()).containsExactly(OcrReviewValidatorTest.finding());
        assertThat(repository.detail(other, saved.recordId())).isEmpty();
        assertThat(service.confirm(user, job, key, body)).isEqualTo(saved);
        service.cancel(user, job);
        assertThat(repository.detail(user, saved.recordId()).orElseThrow().findings()).hasSize(1);
        expectError(() -> service.confirm(user, job, UUID.randomUUID(), body), ErrorCode.OCR_ALREADY_CONFIRMED);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HEAPY_TEST_POSTGRES_URL", matches = ".+")
    void 수치_정성_소견_종합소견을_원자적으로_저장하고_숫자결과를_보존한다() {
        if (repository.itemType("HEARING_GENERAL_LEFT") == null) jdbc.update("""
                insert into public.master_checkup_item(item_code,is_active,value_type)
                values ('HEARING_GENERAL_LEFT',true,'text')
                """);
        var snapshot = OcrReviewValidatorTest.snapshot();
        snapshot.withArray("items").add(OcrReviewValidatorTest.item("n", "FASTING_GLUCOSE", "102", "정상"));
        snapshot.withArray("items").add(OcrReviewValidatorTest.item("q", "HEARING_GENERAL_LEFT", "정상", null));
        Finding opinion = new Finding(1, UUID.randomUUID(), "overall_opinion", null, "종합소견", "합성 권고", null, null, null, null);
        snapshot.withArray("overallOpinions").add(OcrJson.MAPPER.valueToTree(opinion));
        when(gateway.read(any())).thenReturn(new Snapshot("completed", 1, snapshot, null));
        UUID job = service.upload(user, UUID.randomUUID(), "pdf", file("혼합")).jobId();
        var body = OcrReviewValidatorTest.body(List.of(
                new Result("FASTING_GLUCOSE", "100", new BigDecimal("100"), null, "정상", "n"),
                new Result("HEARING_GENERAL_LEFT", "정상", null, null, null, "q")),
                List.of(OcrReviewValidatorTest.finding()), List.of(opinion), List.of());
        var saved = service.confirm(user, job, UUID.randomUUID(), body);
        var detail = repository.detail(user, saved.recordId()).orElseThrow();
        assertThat(detail.results()).hasSize(2);
        assertThat(detail.findings()).hasSize(1);
        assertThat(detail.overallOpinions()).containsExactly(opinion);
        assertThat(detail.results().stream().filter(result -> result.numericValue() != null).toList())
                .singleElement().satisfies(result -> assertThat(result.numericValue()).isEqualByComparingTo("100"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HEAPY_TEST_POSTGRES_URL", matches = ".+")
    void 소견저장뒤_오류가나면_회차_결과_소견_로그_확정상태를_모두롤백한다() {
        var original = OcrReviewValidatorTest.snapshot();
        when(gateway.read(any())).thenReturn(new Snapshot("completed", 1, original, null));
        UUID job = service.upload(user, UUID.randomUUID(), "pdf", file("롤백")).jobId();
        var before = OcrReviewValidatorTest.finding();
        var changed = new Finding(1, before.findingId(), before.classification(), before.examType(), before.examName(),
                "수정한 합성 소견", before.bodySite(), before.method(), before.performedAt(), null);
        var failingRepository = new OcrRepository(jdbc) {
            @Override
            public Confirmed confirm(Job target, Confirmation body, String fingerprint, List<Correction> corrections, Instant now) {
                super.confirm(target, body, fingerprint, corrections, now);
                throw new IllegalStateException("합성 저장 실패");
            }
        };
        var failingService = new OcrService(failingRepository, gateway, new DataSourceTransactionManager(source), Clock.systemUTC(),
                new OcrProperties(true, "ap-northeast-2", "577638373354", "fixture", "fixture"));
        assertThatThrownBy(() -> failingService.confirm(user, job, UUID.randomUUID(),
                OcrReviewValidatorTest.body(List.of(), List.of(changed), List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from public.health_checkup_records where user_id=?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from public.ocr_correction_logs where job_id=?", Integer.class, job)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from private.checkup_ocr_requests where job_id=? and operation='confirm'", Integer.class, job)).isZero();
        assertThat(repository.owned(user, job).orElseThrow().status()).isEqualTo("pending");
        verify(gateway, times(0)).purge(any(), anyString());
    }

    @Test
    void 종료된작업은_늦은완료로_되살아나지않는다() {
        UUID job = service.upload(user, UUID.randomUUID(), "pdf", file("취소")).jobId();
        Job before = repository.owned(user, job).orElseThrow();
        service.cancel(user, job);
        repository.progress(before, new Snapshot("completed", 1, OcrReviewValidatorTest.snapshot(), null), null);
        assertThat(repository.owned(user, job).orElseThrow().status()).isEqualTo("expired");
        expectError(() -> service.get(user, job), ErrorCode.OCR_EXPIRED);
    }

    @Test
    void 배포전_구버전_확정영수증의_요청해시를_유지한다() {
        UUID job = UUID.randomUUID();
        String previousPayload = """
                {"measuredAt":"2026-08-12","providerName":"합성 검진센터",
                "results":[{"itemCode":"FASTING_GLUCOSE","value":"100","numericValue":100,"unit":"mg/dL","status":"정상"}],
                "corrections":[{"fieldKey":"result-1.value","itemCode":"FASTING_GLUCOSE","originalValue":"102","correctedValue":"100","correctionType":"value"}]}
                """;
        assertThat(OcrService.confirmationHash(job, edited())).isEqualTo(OcrJson.hash(job + ":" + OcrJson.encode(OcrJson.MAPPER.readTree(previousPayload))));
    }
}
