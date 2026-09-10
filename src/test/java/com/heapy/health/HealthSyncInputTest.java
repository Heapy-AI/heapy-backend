package com.heapy.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.heapy.checkup.OcrJson;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.service.HealthSyncInput;
import org.junit.jupiter.api.Test;

/** 원본 단위·누락 값·출처 위조를 검증한다. @author 김진우 */
class HealthSyncInputTest {
    @Test void 누락된_활동값은_영이_아니다() {
        var record = HealthSyncInput.record("activity", OcrJson.MAPPER.readTree("""
                {"metric":"activity","externalRecordId":"activity:2026-08-01","sourceUpdatedAt":"2026-08-02T00:00:00Z",
                "data":{"recordDate":"2026-08-01","steps":120}}
                """));
        assertThat(record.values()).containsEntry("steps", 120).containsEntry("floors", null).containsEntry("active_calories_kcal", null);
    }
    @Test void 삭제는_측정값없이_원본ID와_버전으로_처리한다() {
        var record = HealthSyncInput.record("water", OcrJson.MAPPER.readTree("""
                {"metric":"water","externalRecordId":"water:test","sourceUpdatedAt":"2026-08-02T00:00:00Z","operation":"DELETE"}
                """));
        assertThat(record.deleted()).isTrue();
        assertThat(record.values()).isEmpty();
    }
    @Test void 다른항목과_소유자_주입은_거부한다() {
        String valid = """
                {"metric":"water","externalRecordId":"water:test","sourceUpdatedAt":"2026-08-02T00:00:00Z",
                "data":{"consumedAt":"2026-08-01T00:00:00Z","amountMl":250}}
                """;
        for (String invalid : new String[]{valid.replace("water:test", "sleep:test"), valid.replace("250", "-1"),
                valid.replace("\"amountMl\":250", "\"amountMl\":250,\"userId\":\"other\""), valid.replace("\"metric\":\"water\"", "\"metric\":\"bio\"")}) {
            assertThatThrownBy(() -> HealthSyncInput.record("water", OcrJson.MAPPER.readTree(invalid))).isInstanceOf(HeapyException.class);
        }
    }
}
