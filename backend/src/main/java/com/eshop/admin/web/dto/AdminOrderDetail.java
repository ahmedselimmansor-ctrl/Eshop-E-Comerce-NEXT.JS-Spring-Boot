package com.eshop.admin.web.dto;

import com.eshop.order.web.dto.OrderResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * An order as staff see it: the customer's own view plus everything deliberately withheld from them.
 *
 * <p>The internal note, the originating IP and user agent, and every payment attempt — the failures
 * included. None of this appears on any customer-facing endpoint.
 */
@Schema(name = "AdminOrderDetail")
public record AdminOrderDetail(OrderResponse order, String adminNote, String ip, String userAgent,
                               List<PaymentAttempt> payments, List<ReturnSummary> returns) {
}
