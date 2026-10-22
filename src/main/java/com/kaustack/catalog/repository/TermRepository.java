package com.kaustack.catalog.repository;

import com.kaustack.catalog.model.Term;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TermRepository extends JpaRepository<Term, String> {
    Optional<Term> findByTermCode(String termCode);

    // Term codes sort chronologically (e.g. 202701, 202702).
    Optional<Term> findTopByOrderByTermCodeDesc();
}
