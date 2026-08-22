package com.eshop.review.service;

import com.eshop.catalog.service.ProductService;
import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.common.util.HtmlSanitizer;
import com.eshop.review.domain.Review;
import com.eshop.review.domain.ReviewMedia;
import com.eshop.review.domain.ReviewVote;
import com.eshop.review.repository.ReviewRepository;
import com.eshop.review.repository.ReviewVoteRepository;
import com.eshop.review.web.dto.ReviewRequest;
import com.eshop.settings.service.SettingsService;
import com.eshop.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final int MAX_MEDIA_PER_REVIEW = 5;

    private final ReviewRepository reviews;
    private final ReviewVoteRepository votes;
    private final ProductService products;
    private final UserService users;
    private final SettingsService settings;

    /*
     * `media` is left out of the paged entity graphs so Hibernate can page in SQL, which means it
     * has to be touched here — the response is built after this transaction closes and
     * `open-in-view` is off. @BatchSize keeps it to one extra query per page.
     */
    @Transactional(readOnly = true)
    public Page<Review> forProduct(UUID productId, Short rating, Pageable pageable) {
        return withMedia(reviews.findApprovedForProduct(productId, rating, pageable));
    }

    private static Page<Review> withMedia(Page<Review> page) {
        page.forEach(review -> review.getMedia().size());
        return page;
    }

    /** Rating histogram, with an explicit zero for stars nobody has given. */
    @Transactional(readOnly = true)
    public Map<Short, Long> ratingBreakdown(UUID productId) {
        Map<Short, Long> breakdown = new LinkedHashMap<>();
        for (short star = 5; star >= 1; star--) {
            breakdown.put(star, 0L);
        }
        reviews.ratingBreakdown(productId)
                .forEach(bucket -> breakdown.put(bucket.getRating(), bucket.getTotal()));
        return breakdown;
    }

    @Transactional(readOnly = true)
    public Page<Review> forModeration(Review.Status status, Pageable pageable) {
        return withMedia(reviews.findForModeration(status, pageable));
    }

    @Transactional(readOnly = true)
    public Page<Review> byUser(UUID userId, Pageable pageable) {
        return withMedia(reviews.findByUserIdOrderByCreatedAtDesc(userId, pageable));
    }

    /**
     * Submits a review.
     *
     * <p>{@code isVerifiedPurchase} is derived from a delivered order line, never taken from the
     * request — it is a trust signal displayed to other shoppers, so a client that could set it
     * would be able to fabricate credibility. When
     * {@code catalog.reviews_require_purchase} is on, the absence of that order line is a hard
     * rejection rather than merely a missing badge.
     */
    @Transactional
    public Review submit(UUID productId, UUID userId, ReviewRequest request) {
        products.require(productId);

        if (reviews.findByProductIdAndUserId(productId, userId).isPresent()) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "User already reviewed this product")
                    .withViolation("productId", "ALREADY_REVIEWED",
                            "لقد قمت بتقييم هذا المنتج من قبل", productId);
        }

        Optional<UUID> deliveredItem = reviews.findDeliveredOrderItem(productId, userId);

        if (settings.bool(SettingsService.REVIEWS_REQUIRE_PURCHASE, false) && deliveredItem.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Purchase required before reviewing")
                    .withViolation("productId", "PURCHASE_REQUIRED",
                            "يمكنك تقييم المنتج بعد استلامه فقط", productId);
        }

        Review review = new Review();
        review.setProduct(products.require(productId));
        review.setUser(users.require(userId));
        review.setRating(request.rating());
        review.setTitle(HtmlSanitizer.plainText(request.title()));
        review.setBody(HtmlSanitizer.plainText(request.body()));
        review.setVerifiedPurchase(deliveredItem.isPresent());
        deliveredItem.ifPresent(review::setOrderItemId);

        if (settings.bool(SettingsService.REVIEWS_AUTO_APPROVE, false)) {
            review.approve();
        }

        attachMedia(review, request);

        Review saved = reviews.save(review);
        log.info("review_submitted product={} user={} rating={} verified={} status={}",
                productId, userId, request.rating(), review.isVerifiedPurchase(), review.getStatus());
        return saved;
    }

    /** Edits are re-moderated — see {@link Review#editContent}. */
    @Transactional
    public Review edit(UUID reviewId, UUID userId, ReviewRequest request) {
        Review review = requireOwned(reviewId, userId);

        review.editContent(request.rating(),
                HtmlSanitizer.plainText(request.title()),
                HtmlSanitizer.plainText(request.body()));

        review.getMedia().clear();
        attachMedia(review, request);

        if (settings.bool(SettingsService.REVIEWS_AUTO_APPROVE, false)) {
            review.approve();
        }
        return reviews.save(review);
    }

    @Transactional
    public void delete(UUID reviewId, UUID userId) {
        reviews.delete(requireOwned(reviewId, userId));
    }

    /**
     * Records a helpfulness vote, then recomputes the counter from the vote table.
     *
     * <p>Recomputing rather than incrementing keeps the displayed number correct when a voter
     * changes their mind, and makes the operation idempotent under a double-tap.
     */
    @Transactional
    public int vote(UUID reviewId, UUID userId, boolean helpful) {
        Review review = reviews.findById(reviewId)
                .orElseThrow(() -> NotFoundException.of("Review", reviewId));

        votes.findVote(reviewId, userId)
                .ifPresentOrElse(
                        existing -> {
                            existing.setHelpful(helpful);
                            votes.save(existing);
                        },
                        () -> votes.save(new ReviewVote(reviewId, userId, helpful)));

        reviews.refreshHelpfulCount(reviewId);
        return reviews.findById(reviewId).map(Review::getHelpfulCount).orElse(review.getHelpfulCount());
    }

    // ----------------------------------------------------------- moderation --

    @Transactional
    public Review approve(UUID reviewId) {
        Review review = requireById(reviewId);
        review.approve();
        log.info("review_approved id={}", reviewId);
        return reviews.save(review);
    }

    @Transactional
    public Review reject(UUID reviewId, String reason) {
        Review review = requireById(reviewId);
        review.reject(reason);
        log.info("review_rejected id={}", reviewId);
        return reviews.save(review);
    }

    @Transactional
    public Review reply(UUID reviewId, String reply, UUID adminId) {
        Review review = requireById(reviewId);
        review.replyAsAdmin(HtmlSanitizer.plainText(reply), adminId);
        return reviews.save(review);
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return reviews.countByStatus(Review.Status.PENDING);
    }

    // -------------------------------------------------------------- helpers --

    private Review requireById(UUID reviewId) {
        return reviews.findDetailById(reviewId)
                .orElseThrow(() -> NotFoundException.of("Review", reviewId));
    }

    /**
     * Loads a review only if the caller wrote it. A mismatch raises 404 rather than 403, so probing
     * ids cannot be used to enumerate which reviews exist.
     */
    private Review requireOwned(UUID reviewId, UUID userId) {
        return reviews.findById(reviewId)
                .filter(review -> review.getUser().getId().equals(userId))
                .orElseThrow(() -> NotFoundException.of("Review", reviewId));
    }

    private void attachMedia(Review review, ReviewRequest request) {
        if (request.mediaUrls() == null || request.mediaUrls().isEmpty()) {
            return;
        }
        int position = 0;
        for (String url : request.mediaUrls().stream().limit(MAX_MEDIA_PER_REVIEW).toList()) {
            ReviewMedia media = new ReviewMedia();
            media.setReview(review);
            media.setUrl(url);
            media.setPosition(position++);
            review.getMedia().add(media);
        }
    }
}
