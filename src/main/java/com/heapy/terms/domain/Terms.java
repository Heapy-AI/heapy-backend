package com.heapy.terms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "terms", schema = "public")
public class Terms {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "terms_id")
    private Long termsId;

    @Column(name = "terms_code", nullable = false)
    private String termsCode;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "content_url", nullable = false)
    private String contentUrl;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "retired_at")
    private Instant retiredAt;

    protected Terms() {
    }

    public Long getTermsId() {
        return termsId;
    }

    public String getTermsCode() {
        return termsCode;
    }

    public String getVersion() {
        return version;
    }

    public String getTitle() {
        return title;
    }

    public String getContentUrl() {
        return contentUrl;
    }

    public String getContentHash() {
        return contentHash;
    }

    public boolean isRequired() {
        return required;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }
}
