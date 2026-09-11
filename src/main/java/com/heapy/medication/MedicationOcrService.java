package com.heapy.medication;

import com.heapy.checkup.OcrJson;
import com.heapy.checkup.OcrModels.Job;
import com.heapy.checkup.OcrRepository;
import com.heapy.checkup.OcrService;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.time.Clock;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** OCR 후보와 사용자 확정값을 분리하고 약·일정을 한 번만 생성한다. @author 김진우 */
@Service
public class MedicationOcrService {
    private final OcrService ocr;
    private final OcrRepository jobs;
    private final MedicationService medications;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    public MedicationOcrService(OcrService ocr, OcrRepository jobs, MedicationService medications,
            JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.ocr=ocr; this.jobs=jobs; this.medications=medications; this.jdbc=jdbc;
        this.transaction=new TransactionTemplate(manager); this.clock=clock;
    }

    public JsonNode confirm(UUID user, UUID id, UUID key, JsonNode body) {
        if(body==null || !body.isObject() || body.size()!=1 || !body.path("medications").isArray()
                || body.path("medications").isEmpty() || body.path("medications").size()>50
                || OcrJson.encode(body).length()>100_000) MedicationInput.invalid();
        Job initial=owned(user,id);
        JsonNode candidates="confirmed".equals(initial.status())?null:ocr.get(user,id,"medication").result();
        JsonNode result=transaction.execute(tx -> medications.once(user,key,"ocr-confirm:"+id,body,() -> {
            jobs.lockJob(user,id); Job job=owned(user,id);
            if("confirmed".equals(job.status())) throw new HeapyException(ErrorCode.OCR_ALREADY_CONFIRMED);
            if(!clock.instant().isBefore(job.expiresAt()) || "expired".equals(job.status())) throw new HeapyException(ErrorCode.OCR_EXPIRED);
            if(!"review".equals(job.status()) || candidates==null || !candidates.path("items").isArray()) MedicationInput.invalid();
            var response=OcrJson.MAPPER.createObjectNode(); var saved=response.putArray("medications");
            var orders=new HashSet<Integer>();
            for(JsonNode entry:body.path("medications")) {
                if(!entry.isObject() || !entry.path("itemOrder").isIntegralNumber()) MedicationInput.invalid();
                int order=entry.path("itemOrder").asInt();
                if(!orders.add(order)) MedicationInput.invalid();
                JsonNode original=null;
                for(JsonNode candidate:candidates.path("items")) if(candidate.path("itemOrder").asInt()==order) original=candidate;
                if(original==null) { MedicationInput.invalid(); }
                ObjectNode input=(ObjectNode)entry.deepCopy(); input.remove("itemOrder");
                MedicationInput.fields(input); MedicationInput.validate(input);
                JsonNode medication=medications.insert(user,input,id); saved.add(medication);
                jdbc.update("""
                        insert into public.medication_ocr_results(ocr_job_id,item_order,raw_name,raw_dosage,
                        normalized_name,normalized_dosage,confidence,confirmed_data,confirmed_at,medication_id)
                        values(?,?,?,?,?,cast(? as jsonb),?,cast(? as jsonb),current_timestamp,?)
                        """,id,order,text(original,"rawName"),text(original,"rawDosage"),text(original,"normalizedName"),
                        OcrJson.encode(original.path("normalizedDosage")),original.path("confidence").isNumber()?original.path("confidence").decimalValue():null,
                        OcrJson.encode(input),UUID.fromString(medication.path("medicationId").asText()));
            }
            jobs.close(job,"confirmed"); return response;
        }));
        ocr.cleanup(owned(user,id)); return result;
    }
    private Job owned(UUID user,UUID id){return jobs.owned(user,id,"medication").orElseThrow(()->new HeapyException(ErrorCode.RESOURCE_NOT_FOUND));}
    private static String text(JsonNode node,String field){return node.hasNonNull(field)?node.path(field).asText():null;}
}
