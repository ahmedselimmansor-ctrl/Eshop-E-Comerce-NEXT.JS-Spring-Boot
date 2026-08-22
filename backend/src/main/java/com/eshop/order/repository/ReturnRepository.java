package com.eshop.order.repository;

import com.eshop.order.domain.OrderReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Customer-initiated returns. */
@Repository
public interface ReturnRepository extends JpaRepository<OrderReturn, UUID> {

    Optional<OrderReturn> findByReturnNumber(String returnNumber);

    @EntityGraph(attributePaths = {"items", "items.orderItem", "order", "user"})
    Optional<OrderReturn> findDetailById(UUID id);

    List<OrderReturn> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    @EntityGraph(attributePaths = {"order", "user"})
    Page<OrderReturn> findByStatusOrderByCreatedAtDesc(OrderReturn.Status status, Pageable pageable);

    @EntityGraph(attributePaths = {"order", "user"})
    Page<OrderReturn> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Units of an order line already covered by an open or completed return. */
    @Query("""
            SELECT COALESCE(SUM(ri.quantity), 0)
              FROM ReturnItem ri
             WHERE ri.orderItem.id = :orderItemId
               AND ri.orderReturn.status <> 'REJECTED'
            """)
    int quantityAlreadyReturned(@Param("orderItemId") UUID orderItemId);
}
