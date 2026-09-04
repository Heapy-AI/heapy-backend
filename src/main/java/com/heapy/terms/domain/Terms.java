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

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "retired_at")
    private Instant retiredAt;

    protected Terms() {
    }
}
