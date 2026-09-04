package com.heapy.terms.repository;

import com.heapy.terms.domain.UserTermsConsent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserTermsConsentRepository extends JpaRepository<UserTermsConsent, Long> {

    List<UserTermsConsent> findByUserIdAndTermsIdInOrderByOccurredAtDescConsentIdDesc(
            UUID userId,
            Collection<Long> termsIds
    );

    List<UserTermsConsent> findByUserIdAndIdempotencyKeyIn(
            UUID userId,
            Collection<UUID> idempotencyKeys
    );

    @Query(value = """
            with current_required_terms as (
                select terms_id
                from (
                    select t.terms_id,
                           row_number() over (
                               partition by t.terms_code
                               order by t.effective_at desc, t.terms_id desc
                           ) as version_rank
                    from public.terms t
                    where t.is_required = true
                      and t.effective_at <= current_timestamp
                      and (t.retired_at is null or t.retired_at > current_timestamp)
                ) ranked_terms
                where version_rank = 1
            ), latest_consent as (
                select terms_id, action
                from (
                    select c.terms_id,
                           c.action,
                           row_number() over (
                               partition by c.terms_id
                               order by c.occurred_at desc, c.consent_id desc
                           ) as event_rank
                    from public.user_terms_consents c
                    where c.user_id = :userId
                ) ranked_consents
                where event_rank = 1
            )
            select count(*)
            from current_required_terms required_terms
            left join latest_consent consent on consent.terms_id = required_terms.terms_id
            where consent.action is null or consent.action <> 'agreed'
            """, nativeQuery = true)
    long countMissingCurrentRequiredConsents(@Param("userId") UUID userId);
}
