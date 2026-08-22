package com.eshop.order.web.dto;

import com.eshop.payment.domain.PaymentMethod;
import com.eshop.payment.domain.PaymentProviderType;
import com.eshop.user.web.dto.AddressRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Place an order from the caller's current cart.
 *
 * <p>Note what is <em>not</em> here: no line items, no prices, no totals. The cart is the source of
 * truth for what is being bought, and every price is re-read from the catalog inside the checkout
 * transaction. A request that could name its own items and amounts would be a request that could
 * set its own prices.
 *
 * <p>Requires an {@code Idempotency-Key} header. A retry after a dropped response returns the
 * original order rather than creating a second one.
 */
@Schema(name = "CheckoutRequest")
public record CheckoutRequest(

        @Schema(description = "A saved address id. Mutually exclusive with shippingAddress.")
        UUID shippingAddressId,

        @Schema(description = "A one-off address, for guests or a new destination.")
        @Valid AddressRequest shippingAddress,

        @Schema(description = "Defaults to the shipping address when omitted.")
        UUID billingAddressId,

        @NotNull
        UUID shippingMethodId,

        @NotNull
        PaymentProviderType paymentProvider,

        @NotNull
        PaymentMethod paymentMethod,

        @Schema(description = "Revalidated under a lock here; a code accepted on the cart screen "
                + "can still be refused if its last use was taken meanwhile.")
        @Size(max = 40)
        String couponCode,

        @Size(max = 500)
        String customerNote,

        @Schema(description = "Required for guest checkout — where the confirmation is sent.")
        @Email @Size(max = 191)
        String guestEmail,

        @Schema(description = "Required for guest checkout — how the courier makes contact.")
        @Pattern(regexp = "^(\\+20|0)?1[0125][0-9]{8}$", message = "رقم الهاتف غير صحيح")
        String guestPhone
) {

    public boolean hasSavedShippingAddress() {
        return shippingAddressId != null;
    }

    public boolean hasInlineShippingAddress() {
        return shippingAddress != null;
    }
}
