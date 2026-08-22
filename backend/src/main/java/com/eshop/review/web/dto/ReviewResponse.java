package com.eshop.review.web.dto;

import com.eshop.review.domain.Review;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "ReviewResponse")
public record ReviewResponse(
        UUID id,
        short rating,
        String title,
        String body,
        Review.Status status,
        boolean verifiedPurchase,
        int helpfulCount,
        String adminReply,
        Instant adminRepliedAt,
        List<String> mediaUrls,

        @Schema(description = "Display name only - never the reviewer's email")
        String authorName,

        String authorAvatarUrl,
        Instant createdAt
) {

    public static ReviewResponse from(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getRating(),
                review.getTitle(),
                review.getBody(),
                review.getStatus(),
                review.isVerifiedPurchase(),
                review.getHelpfulCount(),
                review.getAdminReply(),
                review.getAdminRepliedAt(),
                review.getMedia().stream().map(media -> media.getUrl()).toList(),
                displayName(review),
                review.getUser().getAvatarUrl(),
                review.getCreatedAt());
    }

    /**
     * First name plus a last initial - "أحمد م." - which reads as a real person without publishing
     * a full name next to a purchase history on an indexable page.
     */
    private static String displayName(Review review) {
        String first = review.getUser().getFirstName();
        String last = review.getUser().getLastName();
        if (last == null || last.isBlank()) {
            return first;
        }
        return first + " " + last.charAt(0) + ".";
    }

    public static List<ReviewResponse> from(List<Review> reviews) {
        return reviews.stream().map(ReviewResponse::from).toList();
    }
}
