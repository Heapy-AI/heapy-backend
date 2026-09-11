package com.heapy.medication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.heapy.checkup.OcrJson;
import com.heapy.common.exception.HeapyException;
import org.junit.jupiter.api.Test;

/** 추측한 시각·잘못된 기간·중복 시각의 등록을 거절한다. @author 김진우 */
class MedicationInputTest {
    @Test void 복용_시각과_기간을_명시해야_한다() {
        var input=OcrJson.MAPPER.createObjectNode().put("displayName","합성 약").put("dosageText","1정")
                .put("startDate","2026-09-11");
        input.putArray("scheduledTimes").add("08:00").add("20:00");
        MedicationInput.fields(input); MedicationInput.validate(input);
        input.putArray("scheduledTimes").add("08:00").add("08:00:00");
        assertThatThrownBy(()->MedicationInput.validate(input)).isInstanceOf(HeapyException.class);
        input.putArray("scheduledTimes").add("25:00");
        assertThatThrownBy(()->MedicationInput.validate(input)).isInstanceOf(HeapyException.class);
        input.putArray("scheduledTimes").add("08:00"); input.put("endDate","2026-09-10");
        assertThatThrownBy(()->MedicationInput.validate(input)).isInstanceOf(HeapyException.class);
        input.putNull("endDate"); input.put("userId","다른 사용자");
        assertThatThrownBy(()->MedicationInput.fields(input)).isInstanceOf(HeapyException.class);
    }
}
