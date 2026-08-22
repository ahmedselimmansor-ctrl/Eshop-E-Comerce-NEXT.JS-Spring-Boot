package com.eshop.review.repository;

import com.eshop.review.domain.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    @EntityGraph(attributePaths = "user")
    @Query("""
            SELECT r FROM Review r
             WHERE r.product.id = :productId
               AND r.status = 'APPROVED'
               AND (CAST(:rating AS INTEGER) IS NULL OR r.rating = :rating)
            """)
    Page<Review> findApprovedForProduct(@Param("productId") UUID productId,
                                        @Param("rating") Short rating,
                                        Pageable pageable);

    @EntityGraph(attributePaths = {"user", "product"})
    @Query("""
            SELECT r FROM Review r
             WHERE (CAST(:status AS STRING) IS NULL OR r.status = :status)
             ORDER BY r.createdAt DESC
            """)
    Page<Review> findForModeration(@Param("status") Review.Status status, Pageable pageable);

    Optional<Review> findByProductIdAndUserId(UUID productId, UUID userId);

    @EntityGraph(attributePaths = {"user", "product"})
    Optional<Review> findDetailById(UUID id);

    Page<Review> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Rating histogram for the product page - "5★ 42, 4★ 11, …".
     *
     * <p>One grouped query rather than five counts, and only over approved reviews so the
     * breakdown adds up to the displayed total.
     */
    @Query("""
            SELECT r.rating AS rating, COUNT(r) AS total
              FROM Review r
             WHERE r.product.id = :productId AND r.status = 'APPROVED'
             GROUP BY r.rating
             ORDER BY r.rating DESC
            """)
    List<RatingBucket> ratingBreakdown(@Param("productId") UUID productId);

    /**
     * Whether this customer actually received this product.
     *
     * <p>Native, because the review domain does not map the order tables. Only {@code DELIVERED}
     * counts - a placed-but-undelivered order is not evidence that anyone has used the item.
     */
    @Query(value = """
            SELECT oi.id
              FROM order_items oi
              JOIN orders o ON o.id = oi.order_id
             WHERE oi.product_id = :productId
               AND o.user_id = :userId
               AND o.status = 'DELIVERED'
             ORDER BY o.delivered_at DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findDeliveredOrderItem(@Param("productId") UUID productId,
                                          @Param("userId") UUID userId);

    @Modifying
    @Query("""
            UPDATE Review r
               SET r.helpfulCount = (SELECT COUNT(v) FROM ReviewVote v
                                      WHERE v.id.reviewId = r.id AND v.helpful = TRUE)
             WHERE r.id = :reviewId
            """)
    void refreshHelpfulCount(@Param("reviewId") UUID reviewId);

    long countByStatus(Review.Status status);

    interface RatingBucket {
        short getRating();

        long getTotal();
    }
}
