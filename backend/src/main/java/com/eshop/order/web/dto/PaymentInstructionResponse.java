package com.eshop.order.web.dto;

import com.eshop.common.money.Money;
import com.eshop.payment.domain.Payment;
import com.eshop.payment.domain.PaymentMethod;
import com.eshop.payment.domain.PaymentProviderType;
import com.eshop.payment.service.PaymentProvider;
import com.eshop.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * What the client must do next to complete a payment.
 *
 * <p>A discriminated union on {@code provider}: Paymob populates {@code iframeUrl}, Fawry populates
 * {@code referenceCode} and {@code expiresAt}, and cash on delivery populates neither. The frontend
 * switches on {@code provider} and renders the matching step.
 */
@Schema(name = "PaymentInstructionResponse")
public record PaymentInstructionResponse(
        UUID paymentId,
        PaymentProviderType provider,
        PaymentMethod method,
        Payment.Status status,
        Money amount,

        @Schema(description = "Paymob: hosted payment page to embed or redirect to")
        String iframeUrl,

        @Schema(description = "Generic off-site redirect target, when the provider uses one")
        String redirectUrl,

        @Schema(description = "Fawry: the number the customer quotes at an outlet")
        String referenceCode,

        @Schema(description = "When the payment window or reference lapses")
        Instant expiresAt,

        @Schema(description = "True when nothing further is required from the customer now")
        boolean actionRequired
) {

    public static PaymentInstructionResponse from(PaymentService.Initiated initiated) {
        Payment payment = initiated.payment();
        PaymentProvider.Initiation initiation = initiated.initiation();

        boolean actionRequired = initiation.iframeUrl() != null
                || initiation.redirectUrl() != null
                || initiation.referenceCode() != null;

        return new PaymentInstructionResponse(
                payment.getId(),
                payment.getProvider(),
                payment.getMethod(),
                payment.getStatus(),
                payment.amountMoney(),
                initiation.iframeUrl(),
                initiation.redirectUrl(),
                initiation.referenceCode(),
                initiation.expiresAt(),
                actionRequired);
    }

    /** Status-only projection, for the polling endpoint the storefront hits after a redirect. */
    public static PaymentInstructionResponse status(Payment payment) {
        return new PaymentInstructionResponse(
                payment.getId(),
                payment.getProvider(),
                payment.getMethod(),
                payment.getStatus(),
                payment.amountMoney(),
                null,
                null,
                payment.getReferenceCode(),
                payment.getExpiresAt(),
                payment.getStatus() == Payment.Status.PENDING);
    }
}
