package com.heapy.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.heapy.checkup.OcrJson;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthMetric;
import com.heapy.health.service.HealthRecordInput;
import org.junit.jupiter.api.Test;

/** 직접 입력의 단위·시각·출처 위조를 검증한다. @author 김진우 */
class HealthRecordInputTest {
    @Test
    void 인슐린은_투약량이_아닌_농도로_저장한다() {
        var body = OcrJson.MAPPER.readTree("{\"bioType\":\"blood_glucose\",\"measuredAt\":\"2026-08-01T00:00:00Z\",\"bloodGlucoseMgDl\":92,\"insulinMicroIuMl\":6.5}");
        var values = HealthRecordInput.manual(HealthMetric.BIO, body);
        assertThat(values.get("insulin_micro_iu_ml").toString()).isEqualTo("6.5");
        assertThat(values).doesNotContainKey("insulin_units");
    }
    @Test
    void 클라이언트가_출처와_소유자를_주입할_수_없다() {
        for (String extra : new String[]{"source", "user_id", "external_record_id"}) {
            var body = OcrJson.MAPPER.readTree("{\"consumedAt\":\"2026-08-01T00:00:00Z\",\"amountMl\":250,\"" + extra + "\":\"위조\"}");
            assertThatThrownBy(() -> HealthRecordInput.manual(HealthMetric.WATER, body)).isInstanceOf(HeapyException.class);
        }
    }

    @Test
    void 수면은_자정을_지날_수_있지만_음수시간과_과다시간은_거부한다() {
        var body = OcrJson.MAPPER.readTree("{\"startAt\":\"2026-08-01T14:00:00Z\",\"endAt\":\"2026-08-01T22:00:00Z\",\"totalSleepMinutes\":450}");
        assertThat(HealthRecordInput.manual(HealthMetric.SLEEP, body)).containsEntry("total_sleep_minutes", 450);
        var invalid = OcrJson.MAPPER.readTree("{\"startAt\":\"2026-08-01T14:00:00Z\",\"endAt\":\"2026-08-01T22:00:00Z\",\"totalSleepMinutes\":490}");
        assertThatThrownBy(() -> HealthRecordInput.manual(HealthMetric.SLEEP, invalid)).isInstanceOf(HeapyException.class);
    }

    @Test
    void 체성분은_BMI를_서버에서_계산한다() {
        var body = OcrJson.MAPPER.readTree("{\"bioType\":\"body_composition\",\"measuredAt\":\"2026-08-01T00:00:00Z\",\"weightKg\":64,\"heightCm\":160}");
        assertThat(HealthRecordInput.manual(HealthMetric.BIO, body).get("bmi_value").toString()).isEqualTo("25.00");
    }

    @Test
    void 영인_물과_뒤집힌_혈압을_거부한다() {
        assertThatThrownBy(() -> HealthRecordInput.manual(HealthMetric.WATER,
                OcrJson.MAPPER.readTree("{\"consumedAt\":\"2026-08-01T00:00:00Z\",\"amountMl\":0}"))).isInstanceOf(HeapyException.class);
        assertThatThrownBy(() -> HealthRecordInput.manual(HealthMetric.BIO,
                OcrJson.MAPPER.readTree("{\"bioType\":\"blood_pressure\",\"measuredAt\":\"2026-08-01T00:00:00Z\",\"systolicMmhg\":70,\"diastolicMmhg\":90}"))).isInstanceOf(HeapyException.class);
    }
}
