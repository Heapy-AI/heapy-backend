package com.heapy.home.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.home.domain.UserHomeModule;
import com.heapy.home.dto.HomeResponse;
import com.heapy.home.repository.UserHomeModuleRepository;
import com.heapy.user.domain.User;
import com.heapy.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HomeServiceTest {

    private static final UUID USER_ID = UUID.fromString("9cf0cf52-b838-4f29-9756-858d45038ca5");

    @Test
    void 데이터가_없어도_네_개의_빈_홈_모듈을_반환한다() {
        UserRepository userRepository = mock(UserRepository.class);
        UserHomeModuleRepository moduleRepository = mock(UserHomeModuleRepository.class);
        User user = new User(USER_ID, new ObjectMapper().createArrayNode(), Instant.now());
        user.completeOnboarding(Instant.now());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(moduleRepository.findByUserIdOrderByDisplayOrder(USER_ID)).thenReturn(List.of(
                new UserHomeModule(USER_ID, "daily_briefing", 1, Instant.now()),
                new UserHomeModule(USER_ID, "key_metrics", 2, Instant.now()),
                new UserHomeModule(USER_ID, "medication", 3, Instant.now()),
                new UserHomeModule(USER_ID, "missions", 4, Instant.now())
        ));

        HomeResponse response = new HomeService(userRepository, moduleRepository).getHome(USER_ID);

        assertThat(response.alerts()).isEmpty();
        assertThat(response.modules()).hasSize(4).allMatch(module -> "empty".equals(module.state()));
    }

    @Test
    void 온보딩_미완료_사용자는_홈에_진입할_수_없다() {
        UserRepository userRepository = mock(UserRepository.class);
        UserHomeModuleRepository moduleRepository = mock(UserHomeModuleRepository.class);
        User user = new User(USER_ID, new ObjectMapper().createArrayNode(), Instant.now());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> new HomeService(userRepository, moduleRepository).getHome(USER_ID))
                .isInstanceOfSatisfying(HeapyException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ONBOARDING_INCOMPLETE)
                );
    }
}
