package com.eshop.review.domain;

import com.eshop.catalog.domain.Product;
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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A customer's rating and written review of a product.
 *
 * <p>Reviews are held for moderation by default. Publishing user-authored text straight to a public
 * page invites spam and abuse, and the store - not the reviewer - is the one whose page it appears
 * on. {@code catalog.reviews_auto_approve} can flip that for stores that would rather moderate
 * after the fact.
 *
 * <p>{@code isVerifiedPurchase} is derived from an actual delivered order line, never supplied by
 * the client. The unique constraint on {@code (product_id, user_id)} means one review per customer
 * per product, so a single account cannot inflate a rating.
 *
 * <p>The aggregate {@code rating_avg}/{@code rating_count} on {@link Product} is maintained by the
 * {@code trg_reviews_rating_rollup} trigger and counts approved reviews only.
 */
@Entity
@Table(name = "reviews")
@Getter
@Setter
@NoArgsConstructor
public class Review extends AuditableEntity {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The delivered order line that entitles this review, when one was found. */
    @Column(name = "order_item_id")
    private UUID orderItemId;

    @Column(name = "rating", nullable = false)
    private short rating;

    @Column(name = "title", length = 120)
    private String title;

    /** Plain text. Any markup is stripped before persistence. */
    @Column(name = "body")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "is_verified_purchase", nullable = false)
    private boolean verifiedPurchase = false;

    @Column(name = "helpful_count", nullable = false)
    private int helpfulCount = 0;

    @Column(name = "admin_reply")
    private String adminReply;

    @Column(name = "admin_replied_at")
    private Instant adminRepliedAt;

    @Column(name = "admin_replied_by")
    private UUID adminRepliedBy;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    /*
     * Left out of the paged entity graphs - Hibernate cannot page in SQL while a collection is
     * join-fetched. @BatchSize loads a whole page's worth in one extra query instead of one per row.
     */
    @BatchSize(size = 50)
    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<ReviewMedia> media = new ArrayList<>();

    public boolean isApproved() {
        return status == Status.APPROVED;
    }

    public void approve() {
        status = Status.APPROVED;
        rejectionReason = null;
    }

    public void reject(String reason) {
        status = Status.REJECTED;
        rejectionReason = reason;
    }

    public void replyAsAdmin(String reply, UUID adminId) {
        adminReply = reply;
        adminRepliedAt = Instant.now();
        adminRepliedBy = adminId;
    }

    /**
     * Edits send the review back for moderation.
     *
     * <p>Without this, a reviewer could get an innocuous review approved and then rewrite it into
     * anything - the approval would still stand.
     */
    public void editContent(short newRating, String newTitle, String newBody) {
        rating = newRating;
        title = newTitle;
        body = newBody;
        status = Status.PENDING;
        rejectionReason = null;
    }
}
