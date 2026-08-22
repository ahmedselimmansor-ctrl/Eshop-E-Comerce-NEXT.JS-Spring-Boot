package com.eshop.admin.web.dto;

import com.eshop.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A refund, as the console sees it.
 *
 * @param completedAt {@code null} while the provider has not yet confirmed. The refund exists and is
 *                    counted against the refundable balance either way, which is what stops a second
 *                    refund being issued while the first is still in flight.
 */
@Schema(name = "RefundResponse")
public record RefundResponse(UUID id, Money amount, String status, Instant completedAt) {
}
