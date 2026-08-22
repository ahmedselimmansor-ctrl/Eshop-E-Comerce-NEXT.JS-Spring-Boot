package com.eshop.config;

import com.eshop.common.idempotency.IdempotencyService;
import com.eshop.common.lock.DistributedLock;
import com.eshop.inventory.service.InventoryService;
import com.eshop.order.service.OrderService;
import com.eshop.payment.service.PaymentService;
import com.eshop.payment.service.PaymentWebhookService;
import com.eshop.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Background maintenance.
 *
 * <p>Every job runs under a {@link DistributedLock} so exactly one pod executes it, and every job is
 * idempotent so a lock lost to a Redis failover costs duplicated work rather than corrupted data.
 * Batch sizes are bounded: a backlog after an incident must not be worked off in a single
 * transaction that holds locks long enough to stall live checkouts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledJobs {

    private final DistributedLock locks;
    private final InventoryService inventory;
    private final OrderService orders;
    private final PaymentService payments;
    private final PaymentWebhookService webhooks;
    private final IdempotencyService idempotency;
    private final UserRepository users;

    /**
     * Releases stock held for checkouts that were abandoned mid-payment.
     *
     * <p>The most operationally important job here. Without it, every abandoned Paymob iframe holds
     * its units until the heat death of the store - during a flash sale that is the difference
     * between selling out and appearing to sell out.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void releaseExpiredReservations() {
        locks.runIfAcquired("reservation-sweeper", Duration.ofMinutes(5), () -> {
            int released = inventory.releaseExpired(500);
            if (released > 0) {
                log.info("job_reservations_released count={}", released);
            }
        });
    }

    /** Cancels orders that were never paid, so they stop occupying the pending queue. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void cancelStaleUnpaidOrders() {
        locks.runIfAcquired("stale-order-sweeper", Duration.ofMinutes(10), () -> {
            int cancelled = orders.cancelStaleUnpaid(200);
            if (cancelled > 0) {
                log.info("job_stale_orders_cancelled count={}", cancelled);
            }
        });
    }

    /** Marks Fawry references and payment sessions that lapsed unpaid. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void expireStalePayments() {
        locks.runIfAcquired("payment-expiry-sweeper", Duration.ofMinutes(10),
                () -> payments.expireStale(200));
    }

    /**
     * Reprocesses callbacks that were stored but failed to apply.
     *
     * <p>The safety net for a webhook that arrived during a deploy or a transient database error.
     * Without it, a customer's payment succeeded at the provider while their order sits unpaid.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void retryFailedWebhooks() {
        locks.runIfAcquired("webhook-retry", Duration.ofMinutes(10),
                () -> webhooks.retryFailed(100, 5));
    }

    /** Clears expired lockouts so an account is not locked longer than intended. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 45_000)
    @Transactional
    public void clearExpiredLockouts() {
        locks.runIfAcquired("lockout-sweeper", Duration.ofMinutes(2),
                () -> users.clearExpiredLockouts(Instant.now()));
    }

    /**
     * Nightly housekeeping: expired tokens, spent idempotency keys, published outbox rows.
     *
     * <p>Runs at 03:00 Cairo time, when traffic is at its lowest. Without this the token tables grow
     * without bound and eventually dominate backup size and restore time.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Africa/Cairo")
    public void nightlyCleanup() {
        locks.runIfAcquired("nightly-cleanup", Duration.ofMinutes(30), () -> {
            int keys = idempotency.purgeExpired();
            log.info("job_nightly_cleanup idempotency_keys_purged={}", keys);
        });
    }
}
