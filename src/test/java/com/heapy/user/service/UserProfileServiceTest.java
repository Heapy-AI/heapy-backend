package com.heapy.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.home.repository.UserHomeModuleRepository;
import com.heapy.terms.repository.UserTermsConsentRepository;
import com.heapy.user.domain.User;
import com.heapy.user.dto.OnboardingCompleteResponse;
import com.heapy.user.dto.UpdateProfileRequest;
import com.heapy.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserProfileServiceTest {

    private static final UUID USER_ID = UUID.fromString("9cf0cf52-b838-4f29-9756-858d45038ca5");

    private UserRepository userRepository;
    private UserTermsConsentRepository consentRepository;
    private UserHomeModuleRepository homeModuleRepository;
    private UserProfileService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        consentRepository = mock(UserTermsConsentRepository.class);
        homeModuleRepository = mock(UserHomeModuleRepository.class);
        service = new UserProfileService(userRepository, consentRepository, homeModuleRepository);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 필수값과_약관이_완료되면_온보딩과_기본_홈_모듈을_생성한다() {
        User user = completedProfileUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(consentRepository.countMissingCurrentRequiredConsents(USER_ID)).thenReturn(0L);
        when(homeModuleRepository.existsByUserId(USER_ID)).thenReturn(false);

        OnboardingCompleteResponse response = service.completeOnboarding(
                USER_ID,
                "4e0a13ec-6ba2-4d72-b2a4-c621f9f0bc9b"
        );

        assertThat(response.onboardingCompleted()).isTrue();
        assertThat(response.nextStep()).isEqualTo("home");
        verify(homeModuleRepository).saveAll(any());
    }

    @Test
    void 필수_약관이_없으면_온보딩을_완료하지_않는다() {
        User user = completedProfileUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(consentRepository.countMissingCurrentRequiredConsents(USER_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.completeOnboarding(
                USER_ID,
                "4e0a13ec-6ba2-4d72-b2a4-c621f9f0bc9b"
        )).isInstanceOfSatisfying(HeapyException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.ONBOARDING_REQUIRED_FIELDS_MISSING)
        );
    }

    @Test
    void 온보딩_단계는_역행할_수_없다() {
        User user = completedProfileUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setOnboardingStep(3);

        assertThatThrownBy(() -> service.updateProfile(USER_ID, request))
                .isInstanceOfSatisfying(HeapyException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ONBOARDING_INCOMPLETE)
                );
    }

    private User completedProfileUser() {
        User user = new User(USER_ID, new ObjectMapper().createArrayNode(), Instant.now());
        user.updateName("김히피");
        user.updateBirthDate(LocalDate.of(1995, 4, 12));
        user.updateSex("Female");
        user.updateHeightCm(new BigDecimal("165.40"));
        user.updateWeightKg(new BigDecimal("58.20"));
        user.updateSmokingStatus("never");
        user.updateAlcoholFrequency("monthly_1_2");
        user.advanceOnboardingStep(6);
        return user;
    }
}
