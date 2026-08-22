package com.eshop.admin.web.dto;

import com.eshop.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to take money for an order — including the ones that failed.
 *
 * <p>Failed attempts are shown, not hidden. They are the first thing anyone investigating a "my card
 * was declined" ticket needs, and a console that lists only the successful capture makes that
 * conversation impossible.
 */
@Schema(name = "PaymentAttempt")
public record PaymentAttempt(UUID id, String provider, String method, String status, Money amount,
                             String cardBrand, String cardLast4, String failureReason,
                             Instant createdAt) {
}
