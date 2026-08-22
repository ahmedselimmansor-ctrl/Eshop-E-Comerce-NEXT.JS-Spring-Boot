package com.eshop.order.domain;

import com.eshop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * An append-only trail of every status change on an order.
 *
 * <p>Answers the question that always comes up in a dispute: who changed this, when, and why. The
 * customer-facing tracking timeline is rendered from these rows, and so is the audit trail an
 * administrator sees.
 */
@Entity
@Table(name = "order_status_history")
@Getter
@Setter
@NoArgsConstructor
public class OrderStatusHistory extends BaseEntity {

    public enum ActorType {
        /** The customer, e.g. cancelling their own order. */
        CUSTOMER,
        ADMIN,
        /** A scheduled job, such as the unpaid-order sweeper. */
        SYSTEM,
        /** A payment provider callback. */
        WEBHOOK
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private OrderStatus toStatus;

    @Column(name = "note")
    private String note;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_type", nullable = false, length = 20)
    private ActorType changedByType = ActorType.SYSTEM;
}
