package com.eshop.order.repository;

import com.eshop.order.domain.Order;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.domain.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @EntityGraph(attributePaths = {"items", "user"})
    Optional<Order> findByOrderNumber(String orderNumber);

    @EntityGraph(attributePaths = {"items", "items.product", "user", "history"})
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findDetailById(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"items", "items.product", "user", "history"})
    @Query("SELECT o FROM Order o WHERE o.orderNumber = :orderNumber")
    Optional<Order> findDetailByOrderNumber(@Param("orderNumber") String orderNumber);

    @Query("""
            SELECT o FROM Order o
             WHERE o.user.id = :userId
               AND (CAST(:status AS STRING) IS NULL OR o.status = :status)
             ORDER BY o.placedAt DESC
            """)
    Page<Order> findForCustomer(@Param("userId") UUID userId,
                                @Param("status") OrderStatus status,
                                Pageable pageable);

    /**
     * Admin order search.
     *
     * <p>The free-text term matches the order number, the customer's email, or the guest email —
     * the three things a support agent actually has when a customer calls.
     */
    @EntityGraph(attributePaths = {"user"})
    @Query("""
            SELECT o FROM Order o
             WHERE (CAST(:status AS STRING) IS NULL OR o.status = :status)
               AND (CAST(:paymentStatus AS STRING) IS NULL OR o.paymentStatus = :paymentStatus)
               AND (CAST(:from AS java.time.Instant) IS NULL OR o.placedAt >= :from)
               AND (CAST(:to AS java.time.Instant) IS NULL OR o.placedAt <= :to)
               AND (CAST(:query AS STRING) IS NULL
                    OR LOWER(o.orderNumber) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%'))
                    OR LOWER(o.guestEmail) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%'))
                    OR LOWER(o.user.email) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%')))
            """)
    Page<Order> adminSearch(@Param("query") String query,
                            @Param("status") OrderStatus status,
                            @Param("paymentStatus") PaymentStatus paymentStatus,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            Pageable pageable);

    /**
     * Unpaid orders past their reservation window, for the sweeper that releases held stock.
     * Bounded by {@code Pageable} so one large backlog cannot lock the table.
     */
    @Query("""
            SELECT o FROM Order o
             WHERE o.status = 'PENDING_PAYMENT' AND o.placedAt < :cutoff
             ORDER BY o.placedAt ASC
            """)
    List<Order> findStaleUnpaid(@Param("cutoff") Instant cutoff, Pageable pageable);

    // -------------------------------------------------------------- reporting --

    /** Revenue over a window. Excludes cancelled and failed orders, and unpaid ones. */
    @Query("""
            SELECT COALESCE(SUM(o.grandTotal), 0)
              FROM Order o
             WHERE o.placedAt BETWEEN :from AND :to
               AND o.status IN ('PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'PARTIALLY_REFUNDED')
            """)
    BigDecimal revenueBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            SELECT COUNT(o) FROM Order o
             WHERE o.placedAt BETWEEN :from AND :to
               AND o.status NOT IN ('CANCELLED', 'FAILED')
            """)
    long countBetween(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Daily revenue series for the dashboard chart. Native because {@code date_trunc} has no JPQL
     * equivalent and doing it in Java would mean pulling every order into memory.
     */
    @Query(value = """
            SELECT date_trunc(:granularity, placed_at) AS bucket,
                   COALESCE(SUM(grand_total), 0)       AS revenue,
                   COUNT(*)                            AS orders
              FROM orders
             WHERE placed_at BETWEEN :from AND :to
               AND status IN ('PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'PARTIALLY_REFUNDED')
             GROUP BY bucket
             ORDER BY bucket ASC
            """, nativeQuery = true)
    List<RevenuePoint> revenueSeries(@Param("from") Instant from,
                                     @Param("to") Instant to,
                                     @Param("granularity") String granularity);

    @Query("""
            SELECT o.status AS status, COUNT(o) AS total
              FROM Order o
             WHERE o.placedAt BETWEEN :from AND :to
             GROUP BY o.status
            """)
    List<StatusCount> statusBreakdown(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            SELECT oi.product_id                        AS productId,
                   MIN(oi.name_ar)                      AS nameAr,
                   MIN(oi.image_url)                    AS imageUrl,
                   SUM(oi.quantity)                     AS unitsSold,
                   SUM(oi.line_total)                   AS revenue
              FROM order_items oi
              JOIN orders o ON o.id = oi.order_id
             WHERE o.placed_at BETWEEN :from AND :to
               AND o.status IN ('PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'PARTIALLY_REFUNDED')
               AND oi.product_id IS NOT NULL
             GROUP BY oi.product_id
             ORDER BY SUM(oi.quantity) DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<TopProduct> topProducts(@Param("from") Instant from,
                                 @Param("to") Instant to,
                                 @Param("limit") int limit);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status")
    long countByStatus(@Param("status") OrderStatus status);

    /**
     * Reserves the next human-readable order number from the database sequence.
     *
     * <p>Generated by PostgreSQL rather than in Java so the sequence is shared across every pod —
     * two application instances counting independently would collide on the first concurrent
     * checkout, and the number is what a customer quotes to support.
     */
    @Query(value = "SELECT next_order_number()", nativeQuery = true)
    String nextOrderNumber();

    @Query(value = "SELECT next_return_number()", nativeQuery = true)
    String nextReturnNumber();

    interface RevenuePoint {
        Instant getBucket();

        BigDecimal getRevenue();

        long getOrders();
    }

    interface StatusCount {
        OrderStatus getStatus();

        long getTotal();
    }

    interface TopProduct {
        UUID getProductId();

        String getNameAr();

        String getImageUrl();

        long getUnitsSold();

        BigDecimal getRevenue();
    }
}
