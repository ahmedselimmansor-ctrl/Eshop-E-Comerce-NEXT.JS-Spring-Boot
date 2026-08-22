package com.eshop.admin.service;

import com.eshop.admin.web.dto.StockRow;
import com.eshop.inventory.service.InventoryService;
import com.eshop.review.service.ReviewService;
import com.eshop.review.web.dto.ReviewResponse;
import com.eshop.user.domain.UserStatus;
import com.eshop.user.service.UserService;
import com.eshop.user.web.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Mutations for the ops console, each mapped to its response inside the transaction that made it.
 *
 * <p>This class exists to close one specific and repeatable mistake. The domain services return
 * managed entities; the response mappers read lazy associations off them — {@code Review.media},
 * {@code User.roles}, an inventory item's product and warehouse. With {@code open-in-view} disabled,
 * a controller that maps a returned entity is reading proxies whose session has already closed. It
 * compiles cleanly and throws {@code LazyInitializationException} on every single call.
 *
 * <p>Because these methods are {@code @Transactional}, the inner service call joins this transaction
 * and the entity is still managed when the mapper runs, so the lazy reads simply work. The
 * controller receives a finished record and has nothing left to get wrong.
 *
 * <p>Keeping the wrappers here rather than on the domain services also keeps the dependency pointing
 * the right way: the mappers are web-layer types, which the domain has no business knowing about.
 */
@Service
@RequiredArgsConstructor
public class AdminOpsService {

    private final InventoryService inventory;
    private final ReviewService reviews;
    private final UserService users;

    // ------------------------------------------------------------- inventory --

    @Transactional
    public StockRow adjust(UUID itemId, int quantityOnHand, String reason, UUID actorId) {
        return StockRow.from(inventory.adjust(itemId, quantityOnHand, reason, actorId));
    }

    @Transactional
    public StockRow receive(UUID itemId, int quantity, String note, UUID actorId) {
        return StockRow.from(inventory.receive(itemId, quantity, note, actorId));
    }

    // --------------------------------------------------------------- reviews --

    @Transactional
    public ReviewResponse approveReview(UUID reviewId) {
        return ReviewResponse.from(reviews.approve(reviewId));
    }

    @Transactional
    public ReviewResponse rejectReview(UUID reviewId, String reason) {
        return ReviewResponse.from(reviews.reject(reviewId, reason));
    }

    @Transactional
    public ReviewResponse replyToReview(UUID reviewId, String reply, UUID actorId) {
        return ReviewResponse.from(reviews.reply(reviewId, reply, actorId));
    }

    // ------------------------------------------------------------- customers --

    @Transactional(readOnly = true)
    public UserResponse customer(UUID userId) {
        return UserResponse.from(users.require(userId));
    }

    @Transactional
    public UserResponse setCustomerStatus(UUID userId, UserStatus status, UUID actorId) {
        return UserResponse.from(users.setStatus(userId, status, actorId));
    }

    @Transactional
    public UserResponse assignRoles(UUID userId, Set<String> roles, UUID actorId) {
        return UserResponse.from(users.assignRoles(userId, roles, actorId));
    }
}
