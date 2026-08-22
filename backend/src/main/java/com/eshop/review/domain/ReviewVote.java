package com.eshop.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * A "was this helpful?" vote.
 *
 * <p>The composite primary key {@code (review_id, user_id)} is what makes the counter meaningful:
 * one vote per person, enforced by the database rather than by a check the application might skip.
 */
@Entity
@Table(name = "review_votes")
@Getter
@Setter
@NoArgsConstructor
public class ReviewVote {

    @EmbeddedId
    private Key id = new Key();

    @Column(name = "is_helpful", nullable = false)
    private boolean helpful;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public ReviewVote(UUID reviewId, UUID userId, boolean helpful) {
        this.id = new Key(reviewId, userId);
        this.helpful = helpful;
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        @Column(name = "review_id")
        private UUID reviewId;

        @Column(name = "user_id")
        private UUID userId;
    }
}
