package com.eshop.payment.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.common.money.Money;
import com.eshop.common.outbox.OutboxEvent;
import com.eshop.common.outbox.OutboxService;
import com.eshop.config.AppProperties;
import com.eshop.order.domain.Order;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.domain.OrderStatusHistory;
import com.eshop.order.domain.PaymentStatus;
import com.eshop.order.repository.OrderRepository;
import com.eshop.order.web.dto.PaymentInstructionResponse;
import com.eshop.payment.domain.Payment;
import com.eshop.payment.domain.PaymentMethod;
import com.eshop.payment.domain.PaymentProviderType;
import com.eshop.payment.domain.Refund;
import com.eshop.payment.repository.PaymentRepository;
import com.eshop.payment.repository.RefundRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates payments across providers.
 *
 * <p>This class holds the money-handling invariants that no individual provider can enforce:
 * a payment is captured at most once, an order moves to {@code PAID} exactly once, and refunds
 * never exceed what was actually taken.
 *
 * <p>Providers are injected as a list and indexed by type, so adding a gateway is a new
 * {@link PaymentProvider} bean and nothing else. There is no {@code switch} on provider anywhere
 * below.
 */
@Slf4j
@Service
public class PaymentService {

    private final Map<PaymentProviderType, PaymentProvider> providers =
            new EnumMap<>(PaymentProviderType.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final OrderRepository orders;
    private final OutboxService outbox;
    private final AppProperties properties;

    public PaymentService(List<PaymentProvider> discovered,
                          PaymentRepository payments,
                          RefundRepository refunds,
                          OrderRepository orders,
                          OutboxService outbox,
                          AppProperties properties) {
        discovered.forEach(provider -> this.providers.put(provider.type(), provider));
        this.payments = payments;
        this.refunds = refunds;
        this.orders = orders;
        this.outbox = outbox;
        this.properties = properties;

        log.info("payment_providers_registered {}", this.providers.keySet());
    }

    // -------------------------------------------------------------- creation --

    /**
     * Creates a payment for an order and starts it with the provider.
     *
     * <p>Runs <b>after</b> the checkout transaction commits, not inside it. Holding a database
     * transaction open across an HTTP call to a gateway would pin a connection for the duration of
     * someone else's latency, and a slow provider would exhaust the pool under load. The cost is a
     * window where an order exists with no payment row - covered by the reservation sweeper, which
     * releases stock for anything still unpaid.
     *
     * @param idempotencyKey scopes the payment to one checkout attempt; a repeat returns the
     *                       existing payment instead of charging again
     */
    @Transactional
    public Initiated initiate(UUID orderId, PaymentProviderType providerType, PaymentMethod method,
                              String idempotencyKey) {

        Payment existing = payments.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            log.info("payment_initiate_replayed order={} payment={}", orderId, existing.getId());
            return Initiated.fromExisting(existing);
        }

        Order order = orders.findDetailById(orderId)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));

        if (order.getPaymentStatus().isSettled()) {
            throw new BusinessException(ErrorCode.ORDER_ALREADY_PAID,
                    "Order " + order.getOrderNumber() + " is already paid");
        }

        PaymentProvider provider = require(providerType);
        if (!provider.supports(method)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    providerType + " does not support " + method)
                    .withViolation("paymentMethod", "UNSUPPORTED",
                            "طريقة الدفع غير متاحة", method);
        }

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setProvider(providerType);
        payment.setMethod(method);
        payment.setAmount(order.getGrandTotal());
        payment.setCurrency(order.getCurrency());
        payment.setIdempotencyKey(idempotencyKey);
        payment.setStatus(Payment.Status.PENDING);
        payments.saveAndFlush(payment);

        PaymentProvider.Initiation initiation = provider.initiate(new PaymentProvider.PaymentRequest(
                order.getId(),
                order.getOrderNumber(),
                order.grandTotalMoney(),
                method,
                order.contactEmail(),
                contactPhone(order),
                order.contactName(),
                order.getShippingAddress(),
                properties.getPayment().getReturnUrl(),
                order.getLocale()));

        payment.setProviderPaymentId(initiation.providerPaymentId());
        payment.setProviderOrderId(initiation.providerOrderId());
        payment.setReferenceCode(initiation.referenceCode());
        payment.setExpiresAt(initiation.expiresAt());
        payment.setRawResponse(initiation.rawResponse());
        payments.save(payment);

        log.info("payment_initiated order={} provider={} method={} payment={}",
                order.getOrderNumber(), providerType, method, payment.getId());

        return new Initiated(payment, initiation);
    }

    // ------------------------------------------------------------- settlement --

    /**
     * Marks a payment captured and moves its order to {@code PAID}.
     *
     * <p><b>Idempotent by construction.</b> The payment row is locked, and an already-captured
     * payment returns immediately. Providers retry callbacks aggressively, and a second capture
     * would commit the stock reservation twice and send a second confirmation email.
     *
     * @return {@code true} if this call performed the capture, {@code false} if it was already done
     */
    @Transactional
    public boolean capture(UUID paymentId, String providerPaymentId, String cardBrand, String cardLast4,
                           Map<String, Object> redactedResponse) {

        Payment payment = payments.lockById(paymentId)
                .orElseThrow(() -> NotFoundException.of("Payment", paymentId));

        if (payment.isCaptured()) {
            log.info("payment_capture_noop payment={} already_captured", paymentId);
            return false;
        }

        payment.capture(providerPaymentId, redactedResponse);
        payment.applyCardDetails(cardBrand, cardLast4);
        payments.save(payment);

        Order order = payment.getOrder();
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            order.transitionTo(OrderStatus.PAID, "Payment captured via " + payment.getProvider(),
                    null, OrderStatusHistory.ActorType.WEBHOOK);
            orders.save(order);
        }

        // The order confirmation, the stock commit, and the notification all hang off this event,
        // written in the same transaction as the capture so none of them can fire for a payment
        // that then rolls back.
        outbox.record("Order", order.getId(), OutboxEvent.PAYMENT_CAPTURED,
                "orderId", order.getId().toString(),
                "orderNumber", order.getOrderNumber(),
                "paymentId", payment.getId().toString(),
                "provider", payment.getProvider().name(),
                "amount", payment.getAmount().toPlainString(),
                "currency", payment.getCurrency());

        log.info("payment_captured order={} payment={} provider={} amount={}",
                order.getOrderNumber(), paymentId, payment.getProvider(), payment.amountMoney());
        return true;
    }

    @Transactional
    public void markFailed(UUID paymentId, String failureCode, String failureReason,
                           Map<String, Object> redactedResponse) {

        Payment payment = payments.lockById(paymentId)
                .orElseThrow(() -> NotFoundException.of("Payment", paymentId));

        if (payment.isCaptured()) {
            // A late failure notice for a payment that succeeded is noise, not a state change.
            log.warn("payment_failure_after_capture_ignored payment={}", paymentId);
            return;
        }

        payment.fail(failureCode, failureReason, redactedResponse);
        payments.save(payment);

        Order order = payment.getOrder();
        order.setPaymentStatus(PaymentStatus.FAILED);
        orders.save(order);

        outbox.record("Order", order.getId(), OutboxEvent.PAYMENT_FAILED,
                "orderId", order.getId().toString(),
                "orderNumber", order.getOrderNumber(),
                "paymentId", payment.getId().toString(),
                "failureCode", failureCode == null ? "" : failureCode);

        log.warn("payment_failed order={} payment={} code={}",
                order.getOrderNumber(), paymentId, failureCode);
    }

    /**
     * Re-reads a payment from the provider and applies whatever it reports.
     *
     * <p>The reconciliation path for a callback that never arrived - a provider outage, a
     * misconfigured webhook URL, or a delivery that hit us mid-deploy. Also what the storefront
     * polls when the customer returns from the payment page.
     */
    @Transactional
    public Payment reconcile(UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> NotFoundException.of("Payment", paymentId));

        if (payment.isTerminal()) {
            return payment;
        }

        String reference = payment.getProviderPaymentId() != null
                ? payment.getProviderPaymentId()
                : payment.getProviderOrderId();

        if (reference == null) {
            return payment;
        }

        PaymentProvider.Verification verification = require(payment.getProvider()).verify(reference);

        switch (verification.status()) {
            case CAPTURED -> capture(payment.getId(), verification.providerPaymentId(),
                    verification.cardBrand(), verification.cardLast4(), verification.rawResponse());
            case FAILED -> markFailed(payment.getId(), verification.failureCode(),
                    verification.failureReason(), verification.rawResponse());
            case EXPIRED, CANCELLED -> {
                payment.expire();
                payments.save(payment);
            }
            default -> log.debug("payment_reconcile_still_pending payment={}", paymentId);
        }

        return payments.findById(paymentId).orElse(payment);
    }

    // ---------------------------------------------------------------- refunds --

    /**
     * Refunds part or all of a captured payment.
     *
     * <p>The amount is checked against what has already been returned <em>before</em> the provider
     * is called. Over-refunding is not something a gateway will cleanly undo, and the money is
     * already gone by the time the mistake is visible.
     *
     * @param idempotencyKey required - a double-clicked refund button must not pay out twice
     */
    @Transactional
    public Refund refund(UUID orderId, Money amount, String reason, String note,
                         UUID actorId, String idempotencyKey) {

        Refund replayed = refunds.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (replayed != null) {
            log.info("refund_replayed order={} refund={}", orderId, replayed.getId());
            return replayed;
        }

        Payment payment = payments.findCapturedForOrder(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "Order has no captured payment to refund"));

        BigDecimal alreadyRefunded = refunds.totalRefunded(payment.getId());
        Money remaining = payment.amountMoney()
                .minus(Money.of(alreadyRefunded, payment.getCurrency()));

        if (amount.isGreaterThan(remaining)) {
            throw new BusinessException(ErrorCode.REFUND_EXCEEDS_PAYMENT,
                    "Refund of " + amount + " exceeds the remaining " + remaining)
                    .withProperty("remainingRefundable", remaining.amount().toPlainString())
                    .withViolation("amount", "EXCEEDS_REMAINING",
                            "أقصى مبلغ يمكن استرداده " + remaining.amount().toPlainString(), amount.amount());
        }

        Refund refund = new Refund();
        refund.setPayment(payment);
        refund.setOrder(payment.getOrder());
        refund.setAmount(amount.amount());
        refund.setReason(reason);
        refund.setNote(note);
        refund.setCreatedBy(actorId);
        refund.setIdempotencyKey(idempotencyKey);
        refunds.saveAndFlush(refund);

        PaymentProvider.RefundResult result = require(payment.getProvider())
                .refund(payment.getProviderPaymentId() != null
                        ? payment.getProviderPaymentId()
                        : payment.getReferenceCode(), amount, reason);

        if (!result.succeeded()) {
            refund.fail(result.failureReason());
            refunds.save(refund);
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "Refund rejected by provider: " + result.failureReason());
        }

        refund.complete(result.providerRefundId());
        refunds.save(refund);

        applyRefundToOrder(payment, amount, alreadyRefunded, actorId);

        outbox.record("Order", payment.getOrder().getId(), OutboxEvent.REFUND_COMPLETED,
                "orderId", payment.getOrder().getId().toString(),
                "orderNumber", payment.getOrder().getOrderNumber(),
                "refundId", refund.getId().toString(),
                "amount", amount.amount().toPlainString(),
                "currency", amount.currency());

        log.info("refund_completed order={} amount={} actor={}",
                payment.getOrder().getOrderNumber(), amount, actorId);
        return refund;
    }

    /** Moves the order to {@code REFUNDED} or {@code PARTIALLY_REFUNDED} as appropriate. */
    private void applyRefundToOrder(Payment payment, Money justRefunded,
                                    BigDecimal previouslyRefunded, UUID actorId) {
        Order order = payment.getOrder();
        Money total = Money.of(previouslyRefunded, payment.getCurrency()).plus(justRefunded);
        boolean full = !total.isLessThan(payment.amountMoney());

        OrderStatus target = full ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED;
        if (order.getStatus().canTransitionTo(target)) {
            order.transitionTo(target, "Refund of " + justRefunded, actorId,
                    OrderStatusHistory.ActorType.ADMIN);
        } else {
            // The order is somewhere the state machine will not move from (already REFUNDED, say).
            // The payment status still has to reflect reality.
            order.setPaymentStatus(full ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
        }

        payment.setStatus(full ? Payment.Status.REFUNDED : Payment.Status.PARTIALLY_REFUNDED);
        payments.save(payment);
        orders.save(order);
    }

    // ---------------------------------------------------------------- queries --

    @Transactional(readOnly = true)
    public Payment require(UUID paymentId) {
        return payments.findById(paymentId)
                .orElseThrow(() -> NotFoundException.of("Payment", paymentId));
    }

    @Transactional(readOnly = true)
    public List<Payment> forOrder(UUID orderId) {
        return payments.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    @Transactional(readOnly = true)
    public Money totalRefunded(UUID paymentId, String currency) {
        return Money.of(refunds.totalRefunded(paymentId), currency);
    }

    /** Sweeps pending payments whose window has lapsed. Called on a schedule. */
    @Transactional
    public int expireStale(int batchSize) {
        List<Payment> stale = payments.findExpired(Instant.now(),
                org.springframework.data.domain.PageRequest.of(0, batchSize));

        stale.forEach(payment -> {
            payment.expire();
            payments.save(payment);
        });

        if (!stale.isEmpty()) {
            log.info("payments_expired count={}", stale.size());
        }
        return stale.size();
    }

    /** Providers that are configured and usable in this environment. */
    @Transactional(readOnly = true)
    public List<PaymentProviderType> availableProviders() {
        return providers.values().stream()
                .filter(PaymentProvider::isConfigured)
                .map(PaymentProvider::type)
                .toList();
    }

    public PaymentProvider require(PaymentProviderType type) {
        PaymentProvider provider = providers.get(type);
        if (provider == null) {
            throw new BusinessException(ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "No provider registered for " + type);
        }
        if (!provider.isConfigured()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    type + " is not configured in this environment");
        }
        return provider;
    }

    private static String contactPhone(Order order) {
        if (order.getGuestPhone() != null) {
            return order.getGuestPhone();
        }
        Object phone = order.getShippingAddress() == null ? null : order.getShippingAddress().get("phone");
        return phone == null ? "" : String.valueOf(phone);
    }

    /** A created payment plus the instructions the customer needs to complete it. */
    public record Initiated(Payment payment, PaymentProvider.Initiation initiation) {

        static Initiated fromExisting(Payment payment) {
            return new Initiated(payment, new PaymentProvider.Initiation(
                    payment.getProviderPaymentId(),
                    payment.getProviderOrderId(),
                    null,
                    null,
                    payment.getReferenceCode(),
                    payment.getExpiresAt(),
                    false,
                    Map.of()));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean isSettled(UUID orderId) {
        return payments.findCapturedForOrder(orderId).isPresent();
    }

    // -------------------------------------------------------------- responses --

    /** Reconciles with the provider and returns the payment's current instruction. */
    @Transactional
    public PaymentInstructionResponse statusResponse(UUID paymentId) {
        return PaymentInstructionResponse.status(reconcile(paymentId));
    }

    /**
     * Starts a fresh attempt for a payment that failed or expired.
     *
     * <p>A new {@code Payment} row rather than a mutation of the old one, so the failed attempt stays
     * visible for support and reconciliation.
     *
     * <p>The ownership check walks {@code payment.order.user}, two lazy hops. Doing that in a
     * controller reads a detached proxy, and an authorization check that throws instead of deciding
     * is not a check. Here it runs with the session open.
     */
    @Transactional
    public PaymentInstructionResponse retryResponse(UUID paymentId, UUID callerId,
                                                    String idempotencyKey) {
        Payment previous = require(paymentId);

        if (callerId != null
                && previous.getOrder().getUser() != null
                && !previous.getOrder().getUser().getId().equals(callerId)) {
            // Not 403: telling a stranger that a payment exists is itself a disclosure.
            throw NotFoundException.of("Payment", paymentId);
        }

        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? previous.getIdempotencyKey() + ":retry:" + System.currentTimeMillis()
                : idempotencyKey;

        return PaymentInstructionResponse.from(initiate(
                previous.getOrder().getId(),
                previous.getProvider(),
                previous.getMethod(),
                key));
    }

}
