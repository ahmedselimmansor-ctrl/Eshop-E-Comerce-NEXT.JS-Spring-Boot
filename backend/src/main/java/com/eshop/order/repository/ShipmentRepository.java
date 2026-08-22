package com.eshop.order.repository;

import com.eshop.order.domain.Shipment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shipments, which may be several per order. */
@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    @EntityGraph(attributePaths = {"items", "items.orderItem"})
    List<Shipment> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    Optional<Shipment> findByTrackingNumber(String trackingNumber);
}
