package com.eshop.order.domain;

import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One parcel dispatched against an order.
 *
 * <p>An order can produce several shipments - items from different warehouses, or a backordered
 * line that follows later - so the tracking number lives here rather than on the order.
 */
@Entity
@Table(name = "shipments")
@Getter
@Setter
@NoArgsConstructor
public class Shipment extends AuditableEntity {

    public enum Status {
        PENDING,
        IN_TRANSIT,
        OUT_FOR_DELIVERY,
        DELIVERED,
        /** Delivery attempted and failed - wrong address, nobody home, refused. */
        FAILED,
        RETURNED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "carrier", length = 60)
    private String carrier;

    @Column(name = "tracking_number", length = 80)
    private String trackingNumber;

    @Column(name = "tracking_url")
    private String trackingUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status = Status.PENDING;

    /** What the courier charged us - margin data, never shown to the customer. */
    @Column(name = "cost", precision = 12, scale = 2)
    private BigDecimal cost;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ShipmentItem> items = new ArrayList<>();

    public void markShipped() {
        status = Status.IN_TRANSIT;
        shippedAt = Instant.now();
    }

    public void markDelivered() {
        status = Status.DELIVERED;
        deliveredAt = Instant.now();
    }

    public void addItem(ShipmentItem item) {
        item.setShipment(this);
        items.add(item);
    }
}
