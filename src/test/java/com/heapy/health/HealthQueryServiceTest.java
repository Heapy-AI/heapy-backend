package com.heapy.health;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthMetric;
import com.heapy.health.model.HealthPeriod;
import com.heapy.health.repository.HealthRecordRepository;
import com.heapy.health.service.HealthQueryService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 마지막 기록 기준과 사용자 경계를 벗어난 커서 거부를 검증한다. @author 김진우 */
class HealthQueryServiceTest {
    private final HealthRecordRepository repository = mock(HealthRecordRepository.class);
    private final HealthQueryService service = new HealthQueryService(repository);

    @Test void 날짜가_없으면_마지막_기록을_기준으로_한다() {
        UUID user = UUID.randomUUID();
        LocalDate latest = LocalDate.of(2026, 8, 1);
        when(repository.latestDate(user, HealthMetric.WATER, null)).thenReturn(latest);
        when(repository.find(eq(user), eq(HealthMetric.WATER), any(), isNull(), anyInt(), isNull(), isNull())).thenReturn(List.of());
        assertThat(service.find(user, "water", "7d", null, null, null, 100, null).period().to()).isEqualTo(latest);
    }

    @Test void 다른_사용자의_커서는_조회하기_전에_거부한다() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        HealthPeriod period = HealthPeriod.of("7d", date, "raw");
        String scope = UUID.randomUUID() + "|water|" + period.from() + "|" + period.to() + "|null";
        String cursor = Base64.getUrlEncoder().encodeToString((scope + "|2026-08-01T00:00:00Z|" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.find(UUID.randomUUID(), "water", "7d", date, "raw", null, 100, cursor))
                .isInstanceOf(HeapyException.class);
        verifyNoInteractions(repository);
    }
}
