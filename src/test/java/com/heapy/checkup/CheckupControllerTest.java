package com.heapy.checkup;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.heapy.checkup.OcrModels.Detail;
import com.heapy.common.exception.GlobalExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 정식 소견 상세 응답과 사용자 소유권 전달을 검증한다.
 * @author 김진우
 */
class CheckupControllerTest {
    private final UUID user = UUID.randomUUID();
    private final UUID record = UUID.randomUUID();
    private OcrRepository repository;
    private CheckupHistoryService history;
    private MockMvc mvc;

    @BeforeEach
    void 준비() {
        repository = mock(OcrRepository.class);
        history = mock(CheckupHistoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CheckupController(repository, history))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == Jwt.class; }
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory factory) {
                        return Jwt.withTokenValue("fixture").header("alg", "none").subject(user.toString()).build();
                    }
                }).build();
    }

    @Test
    void 목록은_배열과_공통_페이지정보로_반환한다() throws Exception {
        when(history.list(user, 100, null)).thenReturn(new CheckupHistoryService.Page(
                List.of(new CheckupHistoryService.RecordSummary(record, LocalDate.of(2026,8,12), "합성 기관", "ocr", 0, null)),
                new CheckupHistoryService.PageMeta(null, false, 100)));
        mvc.perform(get("/api/checkups").param("limit", "100")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data[0].recordId").value(record.toString()))
                .andExpect(jsonPath("$.data[0].resultCount").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false))
                .andExpect(jsonPath("$.meta.limit").value(100));
    }

    @Test
    void 소견만있는_정식회차의_상세를_빈일반배열과_함께반환한다() throws Exception {
        when(repository.detail(eq(user), eq(record))).thenReturn(Optional.of(new Detail(record, LocalDate.of(2026, 8, 12),
                "합성 검진기관", List.of(), List.of(OcrReviewValidatorTest.finding()), List.of())));
        mvc.perform(get("/api/checkups/" + record)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.results").isEmpty())
                .andExpect(jsonPath("$.data.findings[0].findingId").value(OcrReviewValidatorTest.FINDING_ID.toString()))
                .andExpect(jsonPath("$.data.overallOpinions").isEmpty());
    }

    @Test
    void 본인회차가_아니면_404를_반환한다() throws Exception {
        when(repository.detail(eq(user), eq(record))).thenReturn(Optional.empty());
        mvc.perform(get("/api/checkups/" + record)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
