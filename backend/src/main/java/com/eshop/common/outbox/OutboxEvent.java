package com.eshop.common.outbox;

import com.eshop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A domain event queued for publication, written in the same transaction as the change it describes.
 *
 * <p>This exists to close a gap that has no other safe solution. Publishing to Pub/Sub directly
 * from a service method means either publishing before the commit - and announcing an order that
 * then rolls back - or after it, and losing the event entirely if the process dies in between.
 * Neither is acceptable for "payment captured".
 *
 * <p>Writing the event to this table is part of the business transaction, so it commits atomically
 * with the order. {@code OutboxRelay} then polls and publishes, marking rows as it goes. Delivery is
 * at-least-once, which is why every consumer must be idempotent.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent extends BaseEntity {

    /** Event names. Also the Pub/Sub topic suffix - see {@code AppProperties.PubSub#topicFor}. */
    public static final String ORDER_PLACED = "order.placed";
    public static final String ORDER_PAID = "order.paid";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String ORDER_SHIPPED = "order.shipped";
    public static final String ORDER_DELIVERED = "order.delivered";
    public static final String PAYMENT_CAPTURED = "payment.captured";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String REFUND_COMPLETED = "refund.completed";
    public static final String PRODUCT_UPDATED = "product.updated";
    public static final String PRODUCT_DELETED = "product.deleted";
    public static final String STOCK_LOW = "stock.low";
    public static final String REVIEW_SUBMITTED = "review.submitted";

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    /** {@code null} until the relay succeeds. Indexed so the relay scans only unpublished rows. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private short attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    public static OutboxEvent of(String aggregateType, UUID aggregateId,
                                 String eventType, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payload;
        return event;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public void markPublished() {
        publishedAt = Instant.now();
        lastError = null;
    }

    public void recordFailure(String error) {
        attempts = (short) (attempts + 1);
        // Bounded so one poison payload cannot fill the log with a megabyte per attempt.
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }
}
