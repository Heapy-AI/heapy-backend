package com.heapy.terms.repository;

import com.heapy.terms.domain.Terms;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TermsRepository extends JpaRepository<Terms, Long> {

    @Query("""
            select terms
            from Terms terms
            where terms.effectiveAt <= :now
              and (terms.retiredAt is null or terms.retiredAt > :now)
            order by terms.required desc, terms.termsCode asc, terms.effectiveAt desc
            """)
    List<Terms> findCurrentTerms(@Param("now") Instant now);
}
