package com.eshop.promotion.repository;

import com.eshop.promotion.domain.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    @EntityGraph(attributePaths = "targets")
    Optional<Coupon> findByCode(String code);

    /**
     * Loads the coupon for redemption with a row lock held.
     *
     * <p>Two customers racing for the last use of a limited coupon would otherwise both read
     * "one remaining" and both redeem. The lock serialises them; the second sees the true count.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.code = :code")
    Optional<Coupon> lockByCode(@Param("code") String code);

    boolean existsByCode(String code);

    @Query("""
            SELECT c FROM Coupon c
             WHERE (:activeOnly = FALSE OR c.active = TRUE)
               AND (CAST(:query AS STRING) IS NULL OR LOWER(c.code) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%')))
             ORDER BY c.createdAt DESC
            """)
    Page<Coupon> search(@Param("query") String query,
                        @Param("activeOnly") boolean activeOnly,
                        Pageable pageable);

    @Modifying
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount + 1 WHERE c.id = :id")
    void incrementUsedCount(@Param("id") UUID id);
}
