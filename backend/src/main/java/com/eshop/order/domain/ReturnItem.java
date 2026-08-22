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

/** One line of a return request. */
@Entity
@Table(name = "return_items")
@Getter
@Setter
@NoArgsConstructor
public class ReturnItem extends BaseEntity {

    /**
     * The condition goods arrived in, recorded at receipt.
     *
     * <p>Drives whether the units go back on the shelf: {@code NEW} is restocked, {@code DAMAGED}
     * is written off with a {@code DAMAGE} movement instead.
     */
    public enum Condition {
        NEW,
        USED,
        DAMAGED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false)
    private OrderReturn orderReturn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition", length = 20)
    private Condition condition;

    public boolean isResellable() {
        return condition == Condition.NEW;
    }
}
