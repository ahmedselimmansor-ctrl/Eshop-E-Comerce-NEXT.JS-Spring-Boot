package com.eshop.promotion.repository;

import com.eshop.promotion.domain.Promotion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    /** Currently running promotions, highest priority first. */
    @Query("""
            SELECT p FROM Promotion p
             WHERE p.active = TRUE
               AND (p.startsAt IS NULL OR p.startsAt <= :now)
               AND (p.endsAt IS NULL OR p.endsAt > :now)
             ORDER BY p.priority DESC, p.createdAt ASC
            """)
    List<Promotion> findRunning(@Param("now") Instant now);

    Page<Promotion> findAllByOrderByPriorityDescCreatedAtDesc(Pageable pageable);
}
