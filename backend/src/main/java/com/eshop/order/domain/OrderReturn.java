package com.eshop.order.domain;

import com.eshop.common.domain.AuditableEntity;
import com.eshop.user.domain.User;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A customer's request to send items back.
 *
 * <p>Named {@code OrderReturn} rather than {@code Return} because {@code return} is a Java keyword
 * and a class called {@code Return} reads badly at every call site. The table is {@code returns}.
 *
 * <p>Approval and physical receipt are separate steps. Stock is only restocked on
 * {@code RECEIVED} - crediting inventory on approval would let a customer who never posts the
 * parcel inflate available stock, and the resulting oversell surfaces days later as a cancelled
 * order for someone else.
 */
@Entity
@Table(name = "returns")
@Getter
@Setter
@NoArgsConstructor
public class OrderReturn extends AuditableEntity {

    public enum Status {
        REQUESTED,
        APPROVED,
        REJECTED,
        /** Goods physically back in the warehouse; this is when stock is credited. */
        RECEIVED,
        REFUNDED
    }

    public enum Reason {
        DAMAGED,
        WRONG_ITEM,
        NOT_AS_DESCRIBED,
        CHANGED_MIND,
        SIZE_ISSUE,
        DEFECTIVE,
        OTHER
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Customer-facing reference, {@code RT-YYMMDD-NNNNN}. */
    @Column(name = "return_number", nullable = false, unique = true, length = 20)
    private String returnNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40)
    private Reason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.REQUESTED;

    @Column(name = "note")
    private String note;

    @Column(name = "admin_note")
    private String adminNote;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @OneToMany(mappedBy = "orderReturn", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ReturnItem> items = new ArrayList<>();

    public void approve(UUID adminId, String adminNote) {
        status = Status.APPROVED;
        resolve(adminId, adminNote);
    }

    public void reject(UUID adminId, String adminNote) {
        status = Status.REJECTED;
        resolve(adminId, adminNote);
    }

    public void markReceived(UUID adminId) {
        status = Status.RECEIVED;
        resolve(adminId, this.adminNote);
    }

    public void markRefunded() {
        status = Status.REFUNDED;
    }

    private void resolve(UUID adminId, String note) {
        resolvedBy = adminId;
        resolvedAt = Instant.now();
        adminNote = note;
    }

    public void addItem(ReturnItem item) {
        item.setOrderReturn(this);
        items.add(item);
    }

    public int totalQuantity() {
        return items.stream().mapToInt(ReturnItem::getQuantity).sum();
    }
}
