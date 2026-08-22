package com.eshop.cart.repository;

import com.eshop.cart.domain.Cart;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CartRepository extends JpaRepository<Cart, UUID> {

    /**
     * The user's live cart, with items and enough catalog data to render it in one query.
     * The cart is re-read on nearly every page (badge count, mini-cart), so the entity graph
     * matters more here than almost anywhere else.
     */
    @EntityGraph(attributePaths = {"items", "items.product", "items.product.images", "items.variant"})
    @Query("SELECT c FROM Cart c WHERE c.user.id = :userId AND c.status = 'ACTIVE'")
    Optional<Cart> findActiveByUser(@Param("userId") UUID userId);

    @EntityGraph(attributePaths = {"items", "items.product", "items.product.images", "items.variant"})
    @Query("SELECT c FROM Cart c WHERE c.sessionId = :sessionId AND c.status = 'ACTIVE'")
    Optional<Cart> findActiveBySession(@Param("sessionId") String sessionId);

    @EntityGraph(attributePaths = {"items", "items.product", "items.product.images", "items.variant"})
    @Query("SELECT c FROM Cart c WHERE c.id = :id")
    Optional<Cart> findDetailById(@Param("id") UUID id);

    /**
     * Carts abandoned long enough to be worth a recovery email, but not so long that the shopper has
     * forgotten the visit. Only carts that actually contain something are returned.
     */
    @EntityGraph(attributePaths = "user")
    @Query("""
            SELECT c FROM Cart c
             WHERE c.status = 'ACTIVE'
               AND c.itemCount > 0
               AND c.user IS NOT NULL
               AND c.updatedAt BETWEEN :from AND :to
             ORDER BY c.updatedAt ASC
            """)
    List<Cart> findAbandonedForRecovery(@Param("from") Instant from,
                                        @Param("to") Instant to,
                                        Pageable pageable);

    @Modifying
    @Query("UPDATE Cart c SET c.status = 'ABANDONED' WHERE c.status = 'ACTIVE' AND c.expiresAt < :now")
    int expireStaleCarts(@Param("now") Instant now);

    @Query("SELECT COUNT(c) FROM Cart c WHERE c.status = 'ACTIVE' AND c.itemCount > 0")
    long countActiveWithItems();
}
