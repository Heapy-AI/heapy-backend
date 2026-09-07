package com.heapy.user.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapy.user.domain.User;
import com.heapy.user.dto.CompleteProfileRequest;
import com.heapy.user.repository.UserRepository;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:onboarding_atomic;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "heapy.onboarding.require-consents=false"
})
class OnboardingSubmissionTest {
    @Autowired private UserProfileService service;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Validator validator;

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        users.saveAndFlush(new User(id, new ObjectMapper().createArrayNode(), Instant.now()));
        return id;
    }

    private CompleteProfileRequest request() {
        CompleteProfileRequest request = new CompleteProfileRequest();
        request.setName("테스트");
        request.setBirthDate(LocalDate.of(1995, 4, 12));
        request.setSex("Female");
        request.setHeightCm(new BigDecimal("165"));
        request.setWeightKg(new BigDecimal("58"));
        request.setSmokingStatus("never");
        request.setAlcoholFrequency("none");
        return request;
    }

    @Test
    void 전체_프로필과_완료상태를_저장하고_재시도시_홈모듈을_중복생성하지_않는다() {
        UUID id = newUser();
        String key = UUID.randomUUID().toString();
        service.submitOnboarding(id, key, request());
        service.submitOnboarding(id, key, request());
        assertThat(users.findById(id).orElseThrow().getName()).isEqualTo("테스트");
        assertThat(users.findById(id).orElseThrow().getOnboardingCompletedAt()).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from user_home_modules where user_id=?",
                Integer.class, id)).isEqualTo(4);
    }

    @Test
    void 홈모듈_저장에_실패하면_프로필과_완료상태도_롤백한다() {
        UUID id = newUser();
        jdbc.execute("alter table user_home_modules add constraint test_reject_medication check (user_id <> '"
                + id + "' or module_code <> 'medication')");
        try {
            assertThatThrownBy(() -> service.submitOnboarding(id, UUID.randomUUID().toString(), request()))
                    .isInstanceOf(RuntimeException.class);
            User user = users.findById(id).orElseThrow();
            assertThat(user.getName()).isNull();
            assertThat(user.getOnboardingStep()).isEqualTo(1);
            assertThat(user.getOnboardingCompletedAt()).isNull();
            assertThat(jdbc.queryForObject("select count(*) from user_home_modules where user_id=?",
                    Integer.class, id)).isZero();
        } finally {
            jdbc.execute("alter table user_home_modules drop constraint test_reject_medication");
        }
    }

    @Test
    void 필수_본문_누락과_잘못된_범위를_거부한다() {
        assertThat(validator.validate(new CompleteProfileRequest())).hasSize(7);
        CompleteProfileRequest request = request();
        request.setHeightCm(new BigDecimal("999"));
        assertThat(validator.validate(request)).isNotEmpty();
    }
}
