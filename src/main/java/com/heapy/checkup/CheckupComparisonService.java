package com.heapy.checkup;

import com.heapy.checkup.OcrModels.Detail;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 기관 판정을 유지하며 동일 단위 항목의 수치 차이를 비교한다. @author 김진우 */
@Service
public class CheckupComparisonService {
    private final OcrRepository repository;
    public CheckupComparisonService(OcrRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true, timeout = 15)
    public Map<String, Object> compare(UUID user, List<UUID> ids) {
        if (ids.size() != 2 || ids.getFirst().equals(ids.getLast())) throw new HeapyException(ErrorCode.INVALID_INPUT);
        List<Detail> records = ids.stream().map(id -> repository.detail(user, id)
                .orElseThrow(() -> new HeapyException(ErrorCode.RESOURCE_NOT_FOUND))).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> comparisons = new ArrayList<>();
        var previous = records.getFirst();
        var current = records.getLast();
        var codes = new LinkedHashSet<String>();
        previous.results().forEach(item -> codes.add(item.itemCode()));
        current.results().forEach(item -> codes.add(item.itemCode()));
        for (String code : codes) {
            var before = previous.results().stream().filter(item -> code.equals(item.itemCode())).findFirst().orElse(null);
            var after = current.results().stream().filter(item -> code.equals(item.itemCode())).findFirst().orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemCode", code); row.put("previous", before); row.put("current", after);
            BigDecimal change = null;
            String state = "missing";
            if (before != null && after != null) {
                state = "unit_mismatch";
                if (before.unit() != null && before.unit().equals(after.unit())) {
                    state = "non_numeric";
                    if (before.numericValue() != null && after.numericValue() != null) {
                        change = after.numericValue().subtract(before.numericValue()); state = "comparable";
                    }
                }
            }
            row.put("comparisonStatus", state); row.put("change", change); comparisons.add(row);
        }
        result.put("records", records); result.put("comparisons", comparisons);
        return result;
    }
}
