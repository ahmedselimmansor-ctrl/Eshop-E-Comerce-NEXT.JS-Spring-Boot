package com.eshop.order.repository;

import com.eshop.order.domain.ShippingMethod;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shipping methods offered at checkout. */
@Repository
public interface ShippingMethodRepository extends JpaRepository<ShippingMethod, UUID> {

    Optional<ShippingMethod> findByCode(String code);

    @EntityGraph(attributePaths = "zone")
    List<ShippingMethod> findByActiveTrueOrderByPositionAsc();

    /**
     * Methods that can deliver to a governorate: those scoped to a zone covering it, plus any
     * unzoned method, which is available everywhere.
     *
     * <p>The containment check runs in PostgreSQL against the GIN-indexed JSONB array rather
     * than filtering in Java, so adding governorates to a zone needs no code change.
     */
    @Query(value = """
            SELECT m.* FROM shipping_methods m
             LEFT JOIN shipping_zones z ON z.id = m.zone_id
             WHERE m.is_active = TRUE
               AND (m.zone_id IS NULL
                    OR (z.is_active = TRUE AND jsonb_exists(z.governorates, :governorate)))
             ORDER BY m.position ASC
            """, nativeQuery = true)
    List<ShippingMethod> findServing(@Param("governorate") String governorate);

    boolean existsByCode(String code);
}
