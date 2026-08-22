package com.eshop.wishlist.repository;

import com.eshop.wishlist.domain.Wishlist;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WishlistRepository extends JpaRepository<Wishlist, UUID> {

    /**
     * The list plus everything needed to render a product card, in one query.
     *
     * <p>Without the graph this is the textbook N+1: twenty saved items, twenty product loads,
     * twenty image loads. The brand is joined too because the card shows it.
     */
    @EntityGraph(attributePaths = {
            "items", "items.product", "items.product.images", "items.product.brand", "items.variant"})
    @Query("SELECT w FROM Wishlist w WHERE w.user.id = :userId AND w.name = :name")
    Optional<Wishlist> findByOwnerAndName(@Param("userId") UUID userId, @Param("name") String name);

    @EntityGraph(attributePaths = {
            "items", "items.product", "items.product.images", "items.product.brand", "items.variant"})
    @Query("SELECT w FROM Wishlist w WHERE w.shareToken = :token AND w.isPublic = true")
    Optional<Wishlist> findShared(@Param("token") String token);

    /**
     * Membership for a set of products, used to light up the heart icon on a listing page without
     * loading the wishlist itself.
     */
    @Query("""
            SELECT i.product.id FROM WishlistItem i
            WHERE i.wishlist.user.id = :userId AND i.product.id IN :productIds
            """)
    java.util.List<UUID> savedProductIds(@Param("userId") UUID userId,
                                         @Param("productIds") java.util.Collection<UUID> productIds);
}
