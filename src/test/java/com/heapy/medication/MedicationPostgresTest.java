package com.heapy.medication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.heapy.checkup.OcrGateway;
import com.heapy.checkup.OcrJson;
import com.heapy.checkup.OcrModels.Snapshot;
import com.heapy.checkup.OcrProperties;
import com.heapy.checkup.OcrRepository;
import com.heapy.checkup.OcrService;
import com.heapy.common.exception.HeapyException;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 로컬 전용 PostgreSQL에서 실제 SQL과 원자적 OCR 저장을 검증한다. @author 김진우 */
@EnabledIfEnvironmentVariable(named="HEAPY_MEDICATION_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55439/heapy_medication_test")
class MedicationPostgresTest {
    @Test void 이력보존_소유권_중복요청_기간종료_복약완료를_검증한다() throws Exception {
        run((jdbc,manager)->{
            UUID user=UUID.randomUUID(),other=UUID.randomUUID(); jdbc.update("insert into users values(?),(?)",user,other);
            Clock clock=Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"),ZoneOffset.UTC);
            MedicationService service=new MedicationService(jdbc,clock);
            ObjectNode body=body(); UUID key=UUID.randomUUID(); JsonNode created=service.create(user,key,body);
            assertThat(service.create(user,key,body)).isEqualTo(created);
            UUID id=UUID.fromString(created.path("medicationId").asText());
            assertThat(jdbc.queryForObject("select count(*) from medication_intakes",Integer.class)).isEqualTo(14);
            assertThatThrownBy(()->service.detail(other,id)).isInstanceOf(HeapyException.class);
            var changed=body.deepCopy(); changed.put("displayName","수정 약");
            assertThatThrownBy(()->service.create(user,key,changed)).isInstanceOf(HeapyException.class);
            UUID intake=jdbc.queryForObject("select intake_id from medication_intakes order by scheduled_at limit 1",UUID.class);
            UUID actionKey=UUID.randomUUID(); JsonNode action=OcrJson.MAPPER.readTree("{\"actionSource\":\"app\"}");
            JsonNode taken=service.act(user,intake,actionKey,action,"taken");
            assertThat(service.act(user,intake,actionKey,action,"taken")).isEqualTo(taken);
            assertThatThrownBy(()->service.act(user,intake,UUID.randomUUID(),action,"skipped")).isInstanceOf(HeapyException.class);
            service.update(user,id,changed);
            assertThat(jdbc.queryForObject("select medication_name_snapshot from medication_intakes where intake_id=?",String.class,intake)).isEqualTo("합성 약");
            assertThat(jdbc.queryForObject("select count(*) from medication_intakes",Integer.class)).isEqualTo(14);
            service.archive(user,id);
            assertThat(jdbc.queryForObject("select count(*) from medication_intakes",Integer.class)).isEqualTo(1);
            assertThat(service.detail(user,id).path("status").asText()).isEqualTo("archived");
            var expired=body.deepCopy(); expired.put("startDate","2026-09-01").put("endDate","2026-09-10");
            assertThat(service.create(user,UUID.randomUUID(),expired).path("status").asText()).isEqualTo("completed");
            assertThat(service.list(user,"active",null,20).path("items")).isEmpty();
            service.create(other,UUID.randomUUID(),body);
            var later=new MedicationService(jdbc,Clock.fixed(Instant.parse("2026-09-11T04:00:00Z"),ZoneOffset.UTC));
            var page=later.intakes(other,LocalDate.parse("2026-09-11"),LocalDate.parse("2026-09-11"),"all",null,1);
            assertThat(page.path("hasNext").asBoolean()).isTrue();
            UUID missed=jdbc.queryForObject("select intake_id from medication_intakes where user_id=? and status='missed'",UUID.class,other);
            assertThat(later.act(other,missed,UUID.randomUUID(),action,"taken").path("missedAt").isNull()).isFalse();
        });
    }

    @Test void 복약OCR는_다른문서와_분리되고_검수확정이_원자적이다() throws Exception {
        run((jdbc,manager)->{
            UUID user=UUID.randomUUID(); jdbc.update("insert into users values(?)",user);
            OcrRepository repo=new OcrRepository(jdbc); OcrGateway gateway=mock(OcrGateway.class);
            JsonNode result=OcrJson.MAPPER.readTree("{\"items\":[{\"itemOrder\":1,\"rawName\":\"원문\",\"normalizedName\":\"합성 약\",\"normalizedDosage\":\"1정\"}]}");
            when(gateway.read(any())).thenReturn(new Snapshot("completed",1,result,null));
            OcrService ocr=new OcrService(repo,gateway,manager,Clock.systemUTC(),new OcrProperties(true,"ap-northeast-2","000000000000","fixture","fixture"));
            MedicationService med=new MedicationService(jdbc,Clock.systemUTC());
            MedicationOcrService review=new MedicationOcrService(ocr,repo,med,jdbc,manager,Clock.systemUTC());
            var job=ocr.upload(user,UUID.randomUUID(),"pdf",new MockMultipartFile("file","합성.pdf","application/pdf","%PDF-1.4 test".getBytes()),"medication");
            assertThat(job.documentType()).isEqualTo("medication");
            assertThatThrownBy(()->ocr.get(user,job.jobId())).isInstanceOf(HeapyException.class);
            assertThat(ocr.get(user,job.jobId(),"medication").result()).isEqualTo(result);
            var input=body(); input.put("itemOrder",1);
            var confirmation=OcrJson.MAPPER.createObjectNode(); confirmation.putArray("medications").add(input);
            UUID key=UUID.randomUUID(); JsonNode saved=review.confirm(user,job.jobId(),key,confirmation);
            assertThat(review.confirm(user,job.jobId(),key,confirmation)).isEqualTo(saved);
            assertThatThrownBy(()->review.confirm(user,job.jobId(),UUID.randomUUID(),confirmation)).isInstanceOf(HeapyException.class);
            assertThat(jdbc.queryForObject("select count(*) from medication_ocr_results",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select registration_source from user_medications",String.class)).isEqualTo("ocr");
        });
    }

    private static ObjectNode body(){
        return (ObjectNode)OcrJson.MAPPER.readTree("""
                {"displayName":"합성 약","dosageText":"1정","startDate":"2026-09-11","endDate":null,"scheduledTimes":["10:00:00","20:00:00"]}
                """);
    }
    private interface Check {void run(JdbcTemplate jdbc,DataSourceTransactionManager manager);}
    private static void run(Check check) throws Exception {
        try(var source=new HikariDataSource()){
            source.setJdbcUrl(System.getenv("HEAPY_MEDICATION_TEST_URL"));source.setUsername("postgres");source.setPassword("postgres");source.setMaximumPoolSize(1);
            source.addDataSourceProperty("prepareThreshold","0");
            source.addDataSourceProperty("sslmode","disable");
            var jdbc=new JdbcTemplate(source); var manager=new DataSourceTransactionManager(source);
            String fixture=Files.readString(Path.of("src/test/resources/medication-fixture.sql"));
            String migration=Files.readString(Path.of("supabase/migrations/20260911012222_medication_requests.sql"));
            new TransactionTemplate(manager).executeWithoutResult(tx->{tx.setRollbackOnly();jdbc.execute(fixture);jdbc.execute(migration);check.run(jdbc,manager);});
        }
    }
}
