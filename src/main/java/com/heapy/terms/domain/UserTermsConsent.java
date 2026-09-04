package com.heapy.terms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_terms_consents", schema = "public")
public class UserTermsConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consent_id")
    private Long consentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "terms_id", nullable = false)
    private Long termsId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected UserTermsConsent() {
    }
}
