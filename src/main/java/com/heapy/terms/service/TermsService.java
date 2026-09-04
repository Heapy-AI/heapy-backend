package com.heapy.terms.service;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.terms.domain.Terms;
import com.heapy.terms.domain.UserTermsConsent;
import com.heapy.terms.dto.ConsentEventResponse;
import com.heapy.terms.dto.ConsentItemRequest;
import com.heapy.terms.dto.SaveConsentsRequest;
import com.heapy.terms.dto.SaveConsentsResponse;
import com.heapy.terms.dto.TermsResponse;
import com.heapy.terms.repository.TermsRepository;
import com.heapy.terms.repository.UserTermsConsentRepository;
import com.heapy.user.domain.User;
import com.heapy.user.service.UserProfileService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TermsService {

    private final TermsRepository termsRepository;
    private final UserTermsConsentRepository consentRepository;
    private final UserProfileService userProfileService;

    public TermsService(
            TermsRepository termsRepository,
            UserTermsConsentRepository consentRepository,
            UserProfileService userProfileService
    ) {
        this.termsRepository = termsRepository;
        this.consentRepository = consentRepository;
        this.userProfileService = userProfileService;
    }

    @Transactional(readOnly = true)
    public List<TermsResponse> getCurrentTerms(UUID userId, boolean includeOptional) {
        List<Terms> terms = currentLatestTerms().stream()
                .filter(item -> includeOptional || item.isRequired())
                .toList();
        Map<Long, UserTermsConsent> latestConsents = latestConsents(userId, terms);
        return terms.stream()
                .map(item -> new TermsResponse(
                        item.getTermsId(),
                        item.getTermsCode(),
                        item.getVersion(),
                        item.getTitle(),
                        item.getContentUrl(),
                        item.getContentHash(),
                        item.isRequired(),
                        item.getEffectiveAt(),
                        isAgreed(latestConsents.get(item.getTermsId())) ? "agreed" : "not_agreed"
                ))
                .toList();
    }

    @Transactional
    public SaveConsentsResponse saveConsents(
            UUID userId,
            String idempotencyKeyValue,
            SaveConsentsRequest request
    ) {
        UUID requestKey = parseIdempotencyKey(idempotencyKeyValue);
        validateNoDuplicateTerms(request.consents());
        User user = userProfileService.ensureUser(userId);

        Map<Long, Terms> currentTerms = currentLatestTerms().stream()
                .collect(Collectors.toMap(Terms::getTermsId, Function.identity()));
        request.consents().forEach(item -> validateConsent(item, currentTerms));

        Map<Long, UUID> eventKeys = request.consents().stream()
                .collect(Collectors.toMap(
                        ConsentItemRequest::termsId,
                        item -> eventIdempotencyKey(requestKey, item.termsId())
                ));
        List<UserTermsConsent> existing = consentRepository.findByUserIdAndIdempotencyKeyIn(
                userId,
                eventKeys.values()
        );
        if (!existing.isEmpty()) {
            return replayOrReject(existing, request, eventKeys, user);
        }

        Instant occurredAt = Instant.now();
        List<UserTermsConsent> events = request.consents().stream()
                .map(item -> new UserTermsConsent(
                        userId,
                        item.termsId(),
                        item.action(),
                        request.consentSource(),
                        eventKeys.get(item.termsId()),
                        occurredAt
                ))
                .toList();
        return buildResponse(consentRepository.saveAll(events), user);
    }

    private SaveConsentsResponse replayOrReject(
            List<UserTermsConsent> existing,
            SaveConsentsRequest request,
            Map<Long, UUID> eventKeys,
            User user
    ) {
        if (existing.size() != request.consents().size()) {
            throw new HeapyException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        Map<UUID, UserTermsConsent> existingByKey = existing.stream()
                .collect(Collectors.toMap(UserTermsConsent::getIdempotencyKey, Function.identity()));
        for (ConsentItemRequest item : request.consents()) {
            UserTermsConsent event = existingByKey.get(eventKeys.get(item.termsId()));
            if (event == null
                    || !event.getTermsId().equals(item.termsId())
                    || !event.getAction().equals(item.action())
                    || !event.getConsentSource().equals(request.consentSource())) {
                throw new HeapyException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
        }
        return buildResponse(existing, user);
    }

    private SaveConsentsResponse buildResponse(List<UserTermsConsent> events, User user) {
        List<ConsentEventResponse> responses = events.stream()
                .sorted((left, right) -> left.getTermsId().compareTo(right.getTermsId()))
                .map(event -> new ConsentEventResponse(
                        event.getConsentId(),
                        event.getTermsId(),
                        event.getAction(),
                        event.getOccurredAt()
                ))
                .toList();
        boolean requiredCompleted = consentRepository
                .countMissingCurrentRequiredConsents(user.getUserId()) == 0;
        String nextStep = !requiredCompleted
                ? "terms"
                : user.getOnboardingCompletedAt() == null ? "profile" : "home";
        return new SaveConsentsResponse(responses, requiredCompleted, nextStep);
    }

    private void validateConsent(ConsentItemRequest item, Map<Long, Terms> currentTerms) {
        Terms terms = currentTerms.get(item.termsId());
        if (terms == null) {
            throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (terms.isRequired() && "revoked".equals(item.action())) {
            throw new HeapyException(ErrorCode.REQUIRED_TERMS_REVOCATION);
        }
    }

    private void validateNoDuplicateTerms(List<ConsentItemRequest> consents) {
        Set<Long> uniqueTerms = new HashSet<>();
        if (consents.stream().anyMatch(item -> !uniqueTerms.add(item.termsId()))) {
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        }
    }

    private List<Terms> currentLatestTerms() {
        Map<String, Terms> latestByCode = new LinkedHashMap<>();
        termsRepository.findCurrentTerms(Instant.now())
                .forEach(item -> latestByCode.putIfAbsent(item.getTermsCode(), item));
        return List.copyOf(latestByCode.values());
    }

    private Map<Long, UserTermsConsent> latestConsents(UUID userId, List<Terms> terms) {
        if (terms.isEmpty()) {
            return Map.of();
        }
        List<Long> termsIds = terms.stream().map(Terms::getTermsId).toList();
        Map<Long, UserTermsConsent> latest = new HashMap<>();
        consentRepository.findByUserIdAndTermsIdInOrderByOccurredAtDescConsentIdDesc(userId, termsIds)
                .forEach(consent -> latest.putIfAbsent(consent.getTermsId(), consent));
        return latest;
    }

    private boolean isAgreed(UserTermsConsent consent) {
        return consent != null && "agreed".equals(consent.getAction());
    }

    private UUID parseIdempotencyKey(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        }
    }

    private UUID eventIdempotencyKey(UUID requestKey, Long termsId) {
        String source = requestKey + ":" + termsId;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }
}
