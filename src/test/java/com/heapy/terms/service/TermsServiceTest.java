package com.heapy.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.terms.domain.Terms;
import com.heapy.terms.domain.UserTermsConsent;
import com.heapy.terms.dto.ConsentItemRequest;
import com.heapy.terms.dto.SaveConsentsRequest;
import com.heapy.terms.dto.TermsResponse;
import com.heapy.terms.repository.TermsRepository;
import com.heapy.terms.repository.UserTermsConsentRepository;
import com.heapy.user.domain.User;
import com.heapy.user.service.UserProfileService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TermsServiceTest {

    private static final UUID USER_ID = UUID.fromString("9cf0cf52-b838-4f29-9756-858d45038ca5");

    private TermsRepository termsRepository;
    private UserTermsConsentRepository consentRepository;
    private UserProfileService userProfileService;
    private TermsService service;

    @BeforeEach
    void setUp() {
        termsRepository = mock(TermsRepository.class);
        consentRepository = mock(UserTermsConsentRepository.class);
        userProfileService = mock(UserProfileService.class);
        service = new TermsService(termsRepository, consentRepository, userProfileService);
    }

    @Test
    void 필수_약관은_철회할_수_없다() {
        Terms terms = terms(10L, true);
        when(termsRepository.findCurrentTerms(org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(terms));
        when(userProfileService.ensureUser(USER_ID)).thenReturn(mock(User.class));

        SaveConsentsRequest request = new SaveConsentsRequest(
                List.of(new ConsentItemRequest(10L, "revoked")),
                "app"
        );

        assertThatThrownBy(() -> service.saveConsents(
                USER_ID,
                "4e0a13ec-6ba2-4d72-b2a4-c621f9f0bc9b",
                request
        )).isInstanceOfSatisfying(HeapyException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REQUIRED_TERMS_REVOCATION)
        );
    }

    @Test
    void 최신_동의_상태를_약관_응답에_포함한다() {
        Terms terms = terms(10L, true);
        UserTermsConsent consent = mock(UserTermsConsent.class);
        when(consent.getTermsId()).thenReturn(10L);
        when(consent.getAction()).thenReturn("agreed");
        when(termsRepository.findCurrentTerms(org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(terms));
        when(consentRepository.findByUserIdAndTermsIdInOrderByOccurredAtDescConsentIdDesc(
                USER_ID,
                List.of(10L)
        )).thenReturn(List.of(consent));

        List<TermsResponse> response = service.getCurrentTerms(USER_ID, true);

        assertThat(response).singleElement().satisfies(item ->
                assertThat(item.consentStatus()).isEqualTo("agreed")
        );
    }

    private Terms terms(long termsId, boolean required) {
        Terms terms = mock(Terms.class);
        when(terms.getTermsId()).thenReturn(termsId);
        when(terms.getTermsCode()).thenReturn("service");
        when(terms.getVersion()).thenReturn("1.0");
        when(terms.getTitle()).thenReturn("서비스 이용약관");
        when(terms.getContentUrl()).thenReturn("https://example.heapy.app/terms/service/1.0");
        when(terms.getContentHash()).thenReturn("sha256:test");
        when(terms.isRequired()).thenReturn(required);
        when(terms.getEffectiveAt()).thenReturn(Instant.now().minusSeconds(60));
        return terms;
    }
}
