package com.heapy.medication;

import com.heapy.checkup.OcrService;
import com.heapy.common.response.ApiResponse;
import com.heapy.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/** 인증한 사용자의 복약 정보·OCR·복용 행동을 제공한다. @author 김진우 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "복약")
@SecurityRequirement(name = "bearerAuth")
public class MedicationController {
    private final MedicationService service;
    private final OcrService ocr;
    private final MedicationOcrService review;
    public MedicationController(MedicationService service,OcrService ocr,MedicationOcrService review){this.service=service;this.ocr=ocr;this.review=review;}
    @GetMapping("/medications")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="active") String status,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){return ok(service.list(AuthenticatedUser.id(jwt),status,cursor,limit));}
    @PostMapping("/medications")
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") UUID key,@RequestBody JsonNode body){
        return response(201,service.create(AuthenticatedUser.id(jwt),key,body));}
    @GetMapping("/medications/{id}")
    public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return ok(service.detail(AuthenticatedUser.id(jwt),id));}
    @PatchMapping("/medications/{id}")
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody JsonNode body){return ok(service.update(AuthenticatedUser.id(jwt),id,body));}
    @DeleteMapping("/medications/{id}")
    public ResponseEntity<?> archive(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){service.archive(AuthenticatedUser.id(jwt),id);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    @PostMapping(value="/medications/ocr-jobs",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") UUID key,
            @RequestParam String inputType,@RequestPart MultipartFile file){return response(202,ocr.upload(AuthenticatedUser.id(jwt),key,inputType,file,"medication"));}
    @GetMapping("/medications/ocr-jobs/{id}")
    public ResponseEntity<?> job(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return ok(ocr.get(AuthenticatedUser.id(jwt),id,"medication"));}
    @DeleteMapping("/medications/ocr-jobs/{id}")
    public ResponseEntity<?> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){ocr.cancel(AuthenticatedUser.id(jwt),id,"medication");return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    @PostMapping("/medications/ocr-jobs/{id}/confirm")
    public ResponseEntity<?> confirm(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody JsonNode body){return response(201,review.confirm(AuthenticatedUser.id(jwt),id,key,body));}
    @GetMapping("/medication-intakes")
    public ResponseEntity<?> intakes(@AuthenticationPrincipal Jwt jwt,@RequestParam LocalDate from,@RequestParam LocalDate to,
            @RequestParam(defaultValue="all") String status,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit){return ok(service.intakes(AuthenticatedUser.id(jwt),from,to,status,cursor,limit));}
    @PostMapping("/medication-intakes/{id}/complete")
    public ResponseEntity<?> complete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody JsonNode body){return ok(service.act(AuthenticatedUser.id(jwt),id,key,body,"taken"));}
    @PostMapping("/medication-intakes/{id}/skip")
    public ResponseEntity<?> skip(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody JsonNode body){return ok(service.act(AuthenticatedUser.id(jwt),id,key,body,"skipped"));}
    private static ResponseEntity<?> ok(Object data){return response(200,data);}
    private static ResponseEntity<?> response(int status,Object data){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.success(data,"복약 정보를 처리했습니다."));}
}
