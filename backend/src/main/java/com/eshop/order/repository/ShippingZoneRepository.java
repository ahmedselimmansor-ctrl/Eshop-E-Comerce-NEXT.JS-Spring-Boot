package com.eshop.order.repository;

import com.eshop.order.domain.ShippingZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Per-governorate rates and delivery estimates for a shipping method. */
@Repository
public interface ShippingZoneRepository extends JpaRepository<ShippingZone, UUID> {

    List<ShippingZone> findByActiveTrueOrderByNameArAsc();

    // jsonb_exists(...) rather than the `?` operator: Hibernate parses `?` in a native query
    // as a positional parameter placeholder, so the operator form never reaches PostgreSQL.
    @Query(value = """
            SELECT * FROM shipping_zones
             WHERE is_active = TRUE AND jsonb_exists(governorates, :governorate)
             LIMIT 1
            """, nativeQuery = true)
    Optional<ShippingZone> findCovering(@Param("governorate") String governorate);
}
