package com.eshop.payment.web;

import com.eshop.common.exception.NotFoundException;
import com.eshop.order.web.dto.PaymentInstructionResponse;
import com.eshop.payment.domain.Payment;
import com.eshop.payment.service.PaymentService;
import com.eshop.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments")
@SecurityRequirements
public class PaymentController {

    private final PaymentService payments;

    /**
     * Polled by the storefront after the customer returns from a payment page.
     *
     * <p>Deliberately does not wait for the webhook: the callback and the browser redirect race
     * each other, and the redirect usually wins. Calling {@code reconcile} asks the provider
     * directly, so the confirmation screen is correct even when the callback has not landed yet -
     * and it doubles as the recovery path for a callback that never arrives.
     *
     * <p>Guests can reach this: the payment id is a UUIDv7 that was only ever handed to whoever
     * placed the order, and the response exposes no personal data beyond the amount and status.
     */
    @GetMapping("/{paymentId}/status")
    @Operation(summary = "Current payment status",
            description = "Re-reads from the provider when the payment is not yet in a final state.")
    public ResponseEntity<PaymentInstructionResponse> status(@PathVariable UUID paymentId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(payments.statusResponse(paymentId));
    }

    /**
     * Starts a fresh attempt for a payment that failed or expired.
     *
     * <p>A new {@link Payment} row rather than a mutation of the old one, so the failed attempt
     * stays visible for support and reconciliation.
     */
    @org.springframework.web.bind.annotation.PostMapping("/{paymentId}/retry")
    @Operation(summary = "Retry a failed payment")
    public PaymentInstructionResponse retry(@AuthenticationPrincipal UserPrincipal caller,
                                            @PathVariable UUID paymentId,
                                            @RequestParam(required = false) String idempotencyKey) {

        return payments.retryResponse(paymentId, caller == null ? null : caller.id(),
                idempotencyKey);
    }
}
