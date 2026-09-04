package com.heapy.terms.repository;

import com.heapy.terms.domain.Terms;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermsRepository extends JpaRepository<Terms, Long> {
}
