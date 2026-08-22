package com.eshop.notification.domain;

import com.eshop.common.domain.BaseEntity;
import com.eshop.user.domain.User;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * An in-app notification.
 *
 * <p>Both titles and both bodies are stored rather than one rendered string, because a customer can
 * change their language after the notification was created and the inbox should follow. The
 * alternative — rendering at read time from a template key — was rejected: template wording changes
 * over time, and an old notification should keep saying what it originally said.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification extends BaseEntity {

    public enum Channel {
        IN_APP,
        EMAIL,
        PUSH,
        SMS
    }

    /** Notification types. Also the key the mobile app switches on to choose a deep link. */
    public static final String ORDER_PLACED = "ORDER_PLACED";
    public static final String ORDER_PAID = "ORDER_PAID";
    public static final String ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String ORDER_DELIVERED = "ORDER_DELIVERED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String REFUND_COMPLETED = "REFUND_COMPLETED";
    public static final String REVIEW_APPROVED = "REVIEW_APPROVED";
    public static final String PRICE_DROP = "PRICE_DROP";
    public static final String BACK_IN_STOCK = "BACK_IN_STOCK";
    public static final String SECURITY_ALERT = "SECURITY_ALERT";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @Column(name = "title_ar", nullable = false, length = 200)
    private String titleAr;

    @Column(name = "title_en", length = 200)
    private String titleEn;

    @Column(name = "body_ar")
    private String bodyAr;

    @Column(name = "body_en")
    private String bodyEn;

    /** Deep-link payload: {@code {"orderNumber":"EG-260821-00042"}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> data = Map.of();

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel = Channel.IN_APP;

    @Column(name = "read_at")
    private Instant readAt;

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    public String title(String locale) {
        return "en".equalsIgnoreCase(locale) && titleEn != null && !titleEn.isBlank() ? titleEn : titleAr;
    }

    public String body(String locale) {
        return "en".equalsIgnoreCase(locale) && bodyEn != null && !bodyEn.isBlank() ? bodyEn : bodyAr;
    }
}
