package com.heapy.health.model;

import java.time.Instant;
import java.time.LocalDate;
import tools.jackson.databind.JsonNode;

/**
 * 홈 화면의 하루 한 건 브리핑.
 *
 * `evidence_snapshot` 은 담지 않는다. 문장이 주장한 숫자를 나중에 되짚기 위한 값이라
 * 서버에만 둔다. 앱은 headline·chip·body·sections 만 쓴다.
 *
 * @author 고수연
 */
public record HealthBriefing(LocalDate date, String status, String headline, String chip,
                             String body, JsonNode sections, Instant generatedAt) {
    public boolean ready() {
        return "generated".equals(status) && headline != null;
    }
}
