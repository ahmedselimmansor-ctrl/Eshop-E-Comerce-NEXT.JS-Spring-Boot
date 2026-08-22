package com.eshop.review.web;

import com.eshop.common.web.PageResponse;
import com.eshop.review.service.ReviewService;
import com.eshop.review.web.dto.ReviewRequest;
import com.eshop.review.web.dto.ReviewResponse;
import com.eshop.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Reviews")
public class ReviewController {

    private final ReviewService reviews;

    @GetMapping("/products/{productId}/reviews")
    @Operation(summary = "Approved reviews for a product",
            description = "Includes the rating histogram so the summary bars render without a second call.")
    public ProductReviews forProduct(@PathVariable UUID productId,
                                     @RequestParam(required = false) @Min(1) @Max(5) Short rating,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "10") int size,
                                     @RequestParam(defaultValue = "newest") String sort) {

        Sort ordering = switch (sort) {
            case "helpful" -> Sort.by(Sort.Direction.DESC, "helpfulCount", "createdAt");
            case "rating_high" -> Sort.by(Sort.Direction.DESC, "rating", "createdAt");
            case "rating_low" -> Sort.by(Sort.Direction.ASC, "rating").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };

        Page<ReviewResponse> results = reviews
                .forProduct(productId, rating, PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 50), ordering))
                .map(ReviewResponse::from);

        return new ProductReviews(PageResponse.of(results), reviews.ratingBreakdown(productId));
    }

    @PostMapping("/products/{productId}/reviews")
    @Operation(summary = "Submit a review",
            description = "Held for moderation unless the store has auto-approval enabled. "
                    + "The verified-purchase badge is derived from order history, not from the request.")
    public ResponseEntity<ReviewResponse> submit(@AuthenticationPrincipal UserPrincipal caller,
                                                 @PathVariable UUID productId,
                                                 @Valid @RequestBody ReviewRequest request) {
        ReviewResponse created = ReviewResponse.from(reviews.submit(productId, caller.id(), request));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/reviews/{reviewId}")
    @Operation(summary = "Edit your review", description = "Returns the review to moderation.")
    public ReviewResponse edit(@AuthenticationPrincipal UserPrincipal caller,
                               @PathVariable UUID reviewId,
                               @Valid @RequestBody ReviewRequest request) {
        return ReviewResponse.from(reviews.edit(reviewId, caller.id(), request));
    }

    @DeleteMapping("/reviews/{reviewId}")
    @Operation(summary = "Delete your review")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal caller,
                                       @PathVariable UUID reviewId) {
        reviews.delete(reviewId, caller.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reviews/{reviewId}/helpful")
    @Operation(summary = "Mark a review helpful", description = "One vote per customer; re-voting updates it.")
    public Map<String, Integer> vote(@AuthenticationPrincipal UserPrincipal caller,
                                     @PathVariable UUID reviewId,
                                     @RequestParam(defaultValue = "true") boolean helpful) {
        return Map.of("helpfulCount", reviews.vote(reviewId, caller.id(), helpful));
    }

    @GetMapping("/me/reviews")
    @Operation(summary = "Your reviews, including ones still pending")
    public PageResponse<ReviewResponse> mine(@AuthenticationPrincipal UserPrincipal caller,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(reviews
                .byUser(caller.id(), PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 50)))
                .map(ReviewResponse::from));
    }

    /** Page of reviews plus the 5→1 star histogram. */
    public record ProductReviews(PageResponse<ReviewResponse> reviews, Map<Short, Long> ratingBreakdown) {
    }
}
