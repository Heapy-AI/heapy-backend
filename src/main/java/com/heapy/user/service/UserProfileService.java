package com.heapy.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.common.response.FieldErrorResponse;
import com.heapy.home.domain.UserHomeModule;
import com.heapy.home.repository.UserHomeModuleRepository;
import com.heapy.terms.repository.UserTermsConsentRepository;
import com.heapy.user.domain.User;
import com.heapy.user.dto.OnboardingCompleteResponse;
import com.heapy.user.dto.ProfileOptionRequest;
import com.heapy.user.dto.ProfileOptionResponse;
import com.heapy.user.dto.UpdateProfileRequest;
import com.heapy.user.dto.UserProfileResponse;
import com.heapy.user.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {

    private static final List<String> DEFAULT_HOME_MODULES = List.of(
            "daily_briefing",
            "key_metrics",
            "medication",
            "missions"
    );

    private final UserRepository userRepository;
    private final UserTermsConsentRepository consentRepository;
    private final UserHomeModuleRepository homeModuleRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserProfileService(
            UserRepository userRepository,
            UserTermsConsentRepository consentRepository,
            UserHomeModuleRepository homeModuleRepository
    ) {
        this.userRepository = userRepository;
        this.consentRepository = consentRepository;
        this.homeModuleRepository = homeModuleRepository;
    }

    @Transactional
    public User ensureUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseGet(() -> userRepository.save(
                        new User(userId, objectMapper.createArrayNode(), Instant.now())
                ));
    }

    @Transactional
    public UserProfileResponse getProfile(UUID userId) {
        return toResponse(ensureUser(userId));
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = ensureUser(userId);
        applyUpdates(user, request);
        List<FieldErrorResponse> missingFields = missingRequiredFields(user);
        if (user.getOnboardingCompletedAt() != null && !missingFields.isEmpty()) {
            throw new HeapyException(ErrorCode.ONBOARDING_REQUIRED_FIELDS_MISSING, missingFields);
        }
        user.markUpdated(Instant.now());
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public OnboardingCompleteResponse completeOnboarding(UUID userId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        User user = ensureUser(userId);
        if (user.getOnboardingCompletedAt() != null) {
            ensureDefaultHomeModules(userId, user.getOnboardingCompletedAt());
            return completionResponse(user);
        }

        List<FieldErrorResponse> missingFields = missingRequiredFields(user);
        if (consentRepository.countMissingCurrentRequiredConsents(userId) > 0) {
            missingFields.add(new FieldErrorResponse(
                    "requiredConsents",
                    null,
                    "현재 필수 약관에 모두 동의해야 합니다."
            ));
        }
        if (!missingFields.isEmpty()) {
            throw new HeapyException(ErrorCode.ONBOARDING_REQUIRED_FIELDS_MISSING, missingFields);
        }

        Instant completedAt = Instant.now();
        user.completeOnboarding(completedAt);
        userRepository.save(user);
        ensureDefaultHomeModules(userId, completedAt);
        return completionResponse(user);
    }

    private void applyUpdates(User user, UpdateProfileRequest request) {
        if (request.hasName()) {
            String name = normalizedNullable(request.getName());
            if (request.getName() != null && name == null) {
                throw new HeapyException(ErrorCode.INVALID_INPUT);
            }
            user.updateName(name);
        }
        if (request.hasBirthDate()) {
            user.updateBirthDate(request.getBirthDate());
        }
        if (request.hasSex()) {
            user.updateSex(request.getSex());
        }
        if (request.hasHeightCm()) {
            user.updateHeightCm(request.getHeightCm());
        }
        if (request.hasWeightKg()) {
            user.updateWeightKg(request.getWeightKg());
        }
        if (request.hasSmokingStatus()) {
            user.updateSmokingStatus(request.getSmokingStatus());
        }
        if (request.hasAlcoholFrequency()) {
            user.updateAlcoholFrequency(request.getAlcoholFrequency());
        }
        if (request.hasChronicConditions()) {
            user.updateChronicConditions(toJson(request.getChronicConditions()));
        }
        if (request.hasAllergies()) {
            user.updateAllergies(toJson(request.getAllergies()));
        }
        if (request.hasHealthCautions()) {
            user.updateHealthCautions(normalizedNullable(request.getHealthCautions()));
        }
        if (request.hasOnboardingStep()) {
            try {
                user.advanceOnboardingStep(request.getOnboardingStep());
            } catch (IllegalArgumentException exception) {
                throw new HeapyException(ErrorCode.ONBOARDING_INCOMPLETE);
            }
        }
    }

    private List<FieldErrorResponse> missingRequiredFields(User user) {
        List<FieldErrorResponse> errors = new ArrayList<>();
        addMissing(errors, "name", user.getName());
        addMissing(errors, "birthDate", user.getBirthDate());
        addMissing(errors, "sex", user.getSex());
        addMissing(errors, "heightCm", user.getHeightCm());
        addMissing(errors, "weightKg", user.getWeightKg());
        addMissing(errors, "smokingStatus", user.getSmokingStatus());
        addMissing(errors, "alcoholFrequency", user.getAlcoholFrequency());
        if (user.getOnboardingStep() < 6) {
            errors.add(new FieldErrorResponse("onboardingStep", user.getOnboardingStep(), "6단계까지 저장해야 합니다."));
        }
        return errors;
    }

    private void addMissing(List<FieldErrorResponse> errors, String field, Object value) {
        if (value == null) {
            errors.add(new FieldErrorResponse(field, null, "필수값입니다."));
        }
    }

    private ArrayNode toJson(List<ProfileOptionRequest> values) {
        ArrayNode array = objectMapper.createArrayNode();
        if (values == null) {
            return array;
        }
        values.forEach(value -> {
            ObjectNode item = array.addObject();
            if (value.canonicalKey() == null) {
                item.putNull("canonical_key");
            } else {
                item.put("canonical_key", value.canonicalKey().trim());
            }
            item.put("display_name", value.displayName().trim());
            item.put("source", value.source());
        });
        return array;
    }

    private List<ProfileOptionResponse> fromJson(JsonNode values) {
        if (values == null || !values.isArray()) {
            return List.of();
        }
        List<ProfileOptionResponse> result = new ArrayList<>();
        values.forEach(value -> result.add(new ProfileOptionResponse(
                nullableText(value.get("canonical_key")),
                nullableText(value.get("display_name")),
                nullableText(value.get("source"))
        )));
        return List.copyOf(result);
    }

    private String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private String normalizedNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private UserProfileResponse toResponse(User user) {
        boolean requiredConsentCompleted = consentRepository
                .countMissingCurrentRequiredConsents(user.getUserId()) == 0;
        return new UserProfileResponse(
                user.getUserId(),
                user.getName(),
                user.getBirthDate(),
                user.getSex(),
                user.getHeightCm(),
                user.getWeightKg(),
                user.getSmokingStatus(),
                user.getAlcoholFrequency(),
                fromJson(user.getChronicConditions()),
                fromJson(user.getAllergies()),
                user.getHealthCautions(),
                user.getOnboardingStep(),
                user.getOnboardingCompletedAt() != null,
                user.getOnboardingCompletedAt(),
                requiredConsentCompleted
        );
    }

    private void ensureDefaultHomeModules(UUID userId, Instant now) {
        if (homeModuleRepository.existsByUserId(userId)) {
            return;
        }
        List<UserHomeModule> modules = new ArrayList<>();
        for (int index = 0; index < DEFAULT_HOME_MODULES.size(); index++) {
            modules.add(new UserHomeModule(userId, DEFAULT_HOME_MODULES.get(index), index + 1, now));
        }
        homeModuleRepository.saveAll(modules);
    }

    private OnboardingCompleteResponse completionResponse(User user) {
        return new OnboardingCompleteResponse(
                true,
                user.getOnboardingStep(),
                user.getOnboardingCompletedAt(),
                "home"
        );
    }

    private void validateIdempotencyKey(String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        }
    }
}
