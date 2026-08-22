package com.eshop.admin.web;

import com.eshop.admin.service.AdminOpsService;
import com.eshop.admin.web.dto.StockRow;
import com.eshop.catalog.service.ProductSearchPort;
import com.eshop.common.web.PageResponse;
import com.eshop.common.web.RequestContext;
import com.eshop.inventory.service.InventoryService;
import com.eshop.review.domain.Review;
import com.eshop.review.service.ReviewService;
import com.eshop.review.web.dto.ReviewResponse;
import com.eshop.security.UserPrincipal;
import com.eshop.settings.domain.Setting;
import com.eshop.settings.service.SettingsService;
import com.eshop.user.domain.UserStatus;
import com.eshop.user.service.UserService;
import com.eshop.user.web.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The operational corners of the admin API: inventory, customers, review moderation, settings, and
 * search maintenance.
 *
 * <p>Grouped because each is a handful of endpoints that would otherwise be five thin controllers.
 * Permissions are still per-endpoint — this file is an organisational convenience, not a shared
 * authorisation boundary.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin · Operations")
public class AdminOpsController {

    private final InventoryService inventory;
    private final AdminOpsService adminOps;
    private final UserService users;
    private final ReviewService reviews;
    private final SettingsService settings;
    private final ObjectProvider<ProductSearchPort> searchPort;

    // ------------------------------------------------------------- inventory --

    @GetMapping("/inventory")
    @PreAuthorize("hasAuthority('inventory:read')")
    @Operation(summary = "Stock levels",
            description = "`available` is on-hand minus reserved — the figure that decides whether "
                    + "a customer can buy, and the one the low-stock filter uses.")
    public PageResponse<StockRow> inventory(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) UUID warehouseId,
                                            @RequestParam(defaultValue = "false") boolean lowStockOnly,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {

        return PageResponse.of(adminOps.stock(q, warehouseId, lowStockOnly,
                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 100))));
    }

    @PostMapping("/inventory/{itemId}/adjust")
    @PreAuthorize("hasAuthority('inventory:write')")
    @Operation(summary = "Set an absolute on-hand count after a stock take",
            description = "Absolute, not a delta — a counter reports what is on the shelf. "
                    + "Refused with 409 if it would drop below what is already reserved.")
    public StockRow adjust(@AuthenticationPrincipal UserPrincipal caller,
                           @PathVariable UUID itemId,
                           @Valid @RequestBody AdjustRequest request) {

        return adminOps.adjust(itemId, request.quantityOnHand(), request.reason(), caller.id());
    }

    @PostMapping("/inventory/{itemId}/receive")
    @PreAuthorize("hasAuthority('inventory:write')")
    @Operation(summary = "Record goods received from a supplier")
    public StockRow receive(@AuthenticationPrincipal UserPrincipal caller,
                            @PathVariable UUID itemId,
                            @Valid @RequestBody ReceiveRequest request) {

        return adminOps.receive(itemId, request.quantity(), request.note(), caller.id());
    }

    // ------------------------------------------------------------- customers --

    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('customer:read')")
    @Operation(summary = "Search customers by name, email, or phone")
    public PageResponse<UserResponse> customers(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) UserStatus status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {

        return PageResponse.of(users
                .search(q, status, PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 100),
                        Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(UserResponse::summary));
    }

    @GetMapping("/customers/{userId}")
    @PreAuthorize("hasAuthority('customer:read')")
    @Operation(summary = "One customer")
    public UserResponse customer(@PathVariable UUID userId) {
        return adminOps.customer(userId);
    }

    @PostMapping("/customers/{userId}/status")
    @PreAuthorize("hasAuthority('customer:suspend')")
    @Operation(summary = "Suspend or reactivate an account",
            description = "Suspending revokes every session immediately rather than waiting for the "
                    + "access token to expire.")
    public UserResponse setCustomerStatus(@AuthenticationPrincipal UserPrincipal caller,
                                          @PathVariable UUID userId,
                                          @Valid @RequestBody StatusRequest request) {
        return adminOps.setCustomerStatus(userId, request.status(), caller.id());
    }

    @PostMapping("/users/{userId}/roles")
    @PreAuthorize("hasAuthority('role:write')")
    @Operation(summary = "Replace a user's roles",
            description = "Authorities live in the access token, so sessions are revoked to make the "
                    + "change take effect immediately rather than on the next refresh.")
    public UserResponse assignRoles(@AuthenticationPrincipal UserPrincipal caller,
                                    @PathVariable UUID userId,
                                    @Valid @RequestBody RolesRequest request) {
        return adminOps.assignRoles(userId, request.roles(), caller.id());
    }

    // -------------------------------------------------------------- reviews --

    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority('review:moderate')")
    @Operation(summary = "Review moderation queue")
    public PageResponse<ReviewResponse> reviews(@RequestParam(required = false) Review.Status status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {

        return PageResponse.of(reviews
                .forModeration(status, PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 100)))
                .map(ReviewResponse::from));
    }

    @PostMapping("/reviews/{reviewId}/approve")
    @PreAuthorize("hasAuthority('review:moderate')")
    @Operation(summary = "Publish a review")
    public ReviewResponse approveReview(@PathVariable UUID reviewId) {
        return adminOps.approveReview(reviewId);
    }

    @PostMapping("/reviews/{reviewId}/reject")
    @PreAuthorize("hasAuthority('review:moderate')")
    @Operation(summary = "Reject a review")
    public ReviewResponse rejectReview(@PathVariable UUID reviewId,
                                       @Valid @RequestBody RejectRequest request) {
        return adminOps.rejectReview(reviewId, request.reason());
    }

    @PostMapping("/reviews/{reviewId}/reply")
    @PreAuthorize("hasAuthority('review:moderate')")
    @Operation(summary = "Reply publicly to a review")
    public ReviewResponse replyToReview(@AuthenticationPrincipal UserPrincipal caller,
                                        @PathVariable UUID reviewId,
                                        @Valid @RequestBody ReplyRequest request) {
        return adminOps.replyToReview(reviewId, request.reply(), caller.id());
    }

    // ------------------------------------------------------------- settings --

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('settings:write')")
    @Operation(summary = "All store settings")
    public List<SettingRow> settings() {
        return settings.list().stream().map(SettingRow::from).toList();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('settings:write')")
    @Operation(summary = "Update settings",
            description = "Only business configuration lives here. Infrastructure values — database "
                    + "hosts, API keys, bucket names — are environment configuration and are "
                    + "deliberately not editable, so a database row can never redirect traffic or "
                    + "leak a credential.")
    public ResponseEntity<Void> updateSettings(@AuthenticationPrincipal UserPrincipal caller,
                                               @RequestBody Map<String, Object> values) {
        settings.putAll(values, caller.id());
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------------- search --

    @PostMapping("/search/reindex")
    @PreAuthorize("hasAuthority('settings:write')")
    @Operation(summary = "Rebuild the product search index",
            description = "Safe to run at any time — indexing is idempotent and the storefront falls "
                    + "back to PostgreSQL while it runs.")
    public Map<String, Object> reindex() {
        ProductSearchPort port = searchPort.getIfAvailable();
        if (port == null) {
            return Map.of("status", "unavailable", "indexed", 0);
        }
        return Map.of("status", "completed", "indexed", port.reindexAll());
    }

    @GetMapping("/search/status")
    @PreAuthorize("hasAuthority('report:read')")
    @Operation(summary = "Whether the search cluster is answering")
    public Map<String, Object> searchStatus() {
        ProductSearchPort port = searchPort.getIfAvailable();
        return Map.of(
                "configured", port != null,
                "available", port != null && port.isAvailable());
    }

    // ----------------------------------------------------------- payload types --

    public record SettingRow(String key, Object value, String description, boolean publicSetting) {

        static SettingRow from(Setting setting) {
            return new SettingRow(setting.getKey(), setting.getValue(),
                    setting.getDescription(), setting.isPublicSetting());
        }
    }

    public record AdjustRequest(@Min(0) int quantityOnHand,
                                @NotBlank @Size(max = 500) String reason) {
    }

    public record ReceiveRequest(@Min(1) int quantity, @Size(max = 500) String note) {
    }

    public record StatusRequest(@NotNull UserStatus status) {
    }

    public record RolesRequest(@NotEmpty Set<String> roles) {
    }

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record ReplyRequest(@NotBlank @Size(max = 2000) String reply) {
    }
}
