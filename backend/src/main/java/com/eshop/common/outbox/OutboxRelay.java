package com.eshop.common.outbox;

import com.eshop.common.lock.DistributedLock;
import com.eshop.config.AppProperties;
import com.eshop.notification.domain.Notification;
import com.eshop.notification.service.NotificationService;
import com.eshop.notification.service.Recipient;
import com.eshop.inventory.service.InventoryService;
import com.eshop.search.service.ProductIndexer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes queued domain events.
 *
 * <p>Polls {@code outbox_events} and dispatches each one to its handlers: confirmation emails,
 * in-app notifications, search reindexing, and stock commits. This is the piece that makes the
 * transactional outbox actually deliver.
 *
 * <p>Delivery is <b>at-least-once</b>. A crash between "handler ran" and "row marked published"
 * replays the event, so every handler is idempotent — committing an already-committed reservation
 * is a no-op, and reindexing a product twice produces the same document. A duplicate confirmation
 * email is the worst realistic outcome, which is a far better failure mode than a paid order whose
 * stock was never committed.
 *
 * <p>Runs under a cluster-wide lock so only one pod polls at a time.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final DistributedLock locks;
    private final NotificationService notifications;
    private final InventoryService inventory;
    private final ObjectProvider<ProductIndexer> indexer;
    private final AppProperties properties;
    private final TransactionTemplate transactions;

    public OutboxRelay(OutboxRepository outbox,
                       DistributedLock locks,
                       NotificationService notifications,
                       InventoryService inventory,
                       ObjectProvider<ProductIndexer> indexer,
                       AppProperties properties,
                       PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.locks = locks;
        this.notifications = notifications;
        this.inventory = inventory;
        this.indexer = indexer;
        this.properties = properties;
        // An explicit template rather than @Transactional: drain() is called from relay() on the
        // same bean, and a self-invocation never passes through the Spring proxy.
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${eshop.pubsub.outbox-poll-ms:2000}", initialDelay = 15_000)
    public void relay() {
        locks.runIfAcquired("outbox-relay", Duration.ofMinutes(2), this::drain);
    }

    private void drain() {
        AppProperties.PubSub config = properties.getPubsub();

        List<OutboxEvent> pending = transactions.execute(status -> outbox.findPending(
                config.getMaxAttempts(), PageRequest.of(0, config.getOutboxBatchSize())));

        if (pending == null || pending.isEmpty()) {
            return;
        }

        int published = 0;
        for (OutboxEvent event : pending) {
            // One transaction per event, so a failure rolls back only its own work and the rest of
            // the batch still drains.
            Boolean succeeded = transactions.execute(status -> processOne(event.getId()));
            if (Boolean.TRUE.equals(succeeded)) {
                published++;
            }
        }

        log.info("outbox_drained batch={} published={}", pending.size(), published);
    }

    private boolean processOne(UUID eventId) {
        OutboxEvent event = outbox.findById(eventId).orElse(null);
        if (event == null || event.isPublished()) {
            return false;
        }

        try {
            dispatch(event);
            event.markPublished();
            outbox.save(event);
            return true;

        } catch (RuntimeException e) {
            // Recorded on the row rather than rethrown: one poison event must not block the batch
            // behind it. After maxAttempts the row stops being selected and is left for a human.
            event.recordFailure(e.toString());
            outbox.save(event);
            log.error("outbox_dispatch_failed type={} aggregate={} attempt={} error={}",
                    event.getEventType(), event.getAggregateId(), event.getAttempts(), e.toString());
            return false;
        }
    }

    /**
     * Routes an event to its handlers.
     *
     * <p>Deliberately in-process rather than through Pub/Sub. At this scale a topic per event type
     * would add operational surface — subscriptions, dead-letter topics, IAM — for no benefit,
     * since every consumer is this same application. The outbox table remains the durability
     * mechanism either way, so moving a handler to Pub/Sub later means changing this method and
     * nothing else.
     */
    private void dispatch(OutboxEvent event) {
        Map<String, Object> payload = event.getPayload();

        switch (event.getEventType()) {
            case OutboxEvent.ORDER_PLACED -> onOrderPlaced(payload);
            case OutboxEvent.PAYMENT_CAPTURED -> onPaymentCaptured(event.getAggregateId(), payload);
            case OutboxEvent.PAYMENT_FAILED -> onPaymentFailed(payload);
            case OutboxEvent.ORDER_SHIPPED -> onOrderShipped(payload);
            case OutboxEvent.ORDER_DELIVERED -> onOrderDelivered(payload);
            case OutboxEvent.ORDER_CANCELLED -> onOrderCancelled(payload);
            case OutboxEvent.REFUND_COMPLETED -> onRefundCompleted(payload);
            case OutboxEvent.PRODUCT_UPDATED -> indexer.ifAvailable(
                    service -> service.index(event.getAggregateId()));
            case OutboxEvent.PRODUCT_DELETED -> indexer.ifAvailable(
                    service -> service.delete(event.getAggregateId()));
            default -> log.debug("outbox_event_unhandled type={}", event.getEventType());
        }
    }

    // ---------------------------------------------------------------- handlers --

    private void onOrderPlaced(Map<String, Object> payload) {
        Recipient recipient = recipientOf(payload);

        notifications.sendTransactionalEmail(recipient, "order-placed",
                recipient.isArabic() ? "تم استلام طلبك" : "We received your order",
                Map.of("orderNumber", payload.get("orderNumber"),
                        "grandTotal", payload.get("grandTotal"),
                        "currency", payload.get("currency"),
                        "itemCount", payload.get("itemCount"),
                        "orderUrl", orderUrl(payload)));

        userId(payload).ifPresent(userId -> notifications.notifyUser(userId,
                Notification.ORDER_PLACED,
                "تم استلام طلبك", "Order received",
                "طلبك رقم " + payload.get("orderNumber") + " قيد المعالجة",
                "Your order " + payload.get("orderNumber") + " is being processed",
                Map.of("orderNumber", payload.get("orderNumber"))));
    }

    /**
     * The most consequential handler.
     *
     * <p>Committing the stock reservation happens <b>first</b>. If the email fails afterwards the
     * event is retried, and the commit is a no-op the second time — whereas emailing first and then
     * failing to commit would leave paid goods still merely reserved.
     */
    private void onPaymentCaptured(UUID orderId, Map<String, Object> payload) {
        inventory.commitReservations(orderId);

        Recipient recipient = recipientOf(payload);
        notifications.sendTransactionalEmail(recipient, "payment-received",
                recipient.isArabic() ? "تم تأكيد الدفع" : "Payment confirmed",
                Map.of("orderNumber", payload.get("orderNumber"),
                        "amount", payload.get("amount"),
                        "currency", payload.get("currency"),
                        "orderUrl", orderUrl(payload)));

        userId(payload).ifPresent(userId -> notifications.notifyUser(userId,
                Notification.ORDER_PAID,
                "تم تأكيد الدفع", "Payment confirmed",
                "تم استلام مبلغ طلبك رقم " + payload.get("orderNumber"),
                "We received payment for order " + payload.get("orderNumber"),
                Map.of("orderNumber", payload.get("orderNumber"))));
    }

    private void onPaymentFailed(Map<String, Object> payload) {
        userId(payload).ifPresent(userId -> notifications.notifyUser(userId,
                Notification.PAYMENT_FAILED,
                "فشلت عملية الدفع", "Payment failed",
                "لم تتم عملية الدفع لطلبك رقم " + payload.get("orderNumber") + "، يمكنك المحاولة مرة أخرى",
                "Payment for order " + payload.get("orderNumber") + " did not go through — you can try again",
                Map.of("orderNumber", payload.get("orderNumber"))));
    }

    private void onOrderShipped(Map<String, Object> payload) {
        Recipient recipient = recipientOf(payload);

        notifications.sendTransactionalEmail(recipient, "order-shipped",
                recipient.isArabic() ? "طلبك في الطريق" : "Your order is on its way",
                Map.of("orderNumber", payload.get("orderNumber"),
                        "carrier", payload.getOrDefault("carrier", ""),
                        "trackingNumber", payload.getOrDefault("trackingNumber", ""),
                        "orderUrl", orderUrl(payload)));

        userId(payload).ifPresent(userId -> notifications.notifyUser(userId,
                Notification.ORDER_SHIPPED,
                "تم شحن طلبك", "Order shipped",
                "طلبك رقم " + payload.get("orderNumber") + " في الطريق إليك",
                "Order " + payload.get("orderNumber") + " is on its way",
                Map.of("orderNumber", payload.get("orderNumber"),
                        "trackingNumber", payload.getOrDefault("trackingNumber", ""))));
    }

    private void onOrderDelivered(Map<String, Object> payload) {
        userId(payload).ifPresent(userId -> notifications.notifyUser(userId,
                Notification.ORDER_DELIVERED,
                "تم توصيل طلبك", "Order delivered",
                "نتمنى أن ينال إعجابك — شاركنا رأيك",
                "We hope you like it — tell us what you think",
                Map.of("orderNumber", payload.get("orderNumber"))));
    }

    private void onOrderCancelled(Map<String, Object> payload) {
        Recipient recipient = recipientOf(payload);

        notifications.sendTransactionalEmail(recipient, "order-cancelled",
                recipient.isArabic() ? "تم إلغاء طلبك" : "Your order was cancelled",
                Map.of("orderNumber", payload.get("orderNumber"),
                        "reason", payload.getOrDefault("reason", "")));
    }

    private void onRefundCompleted(Map<String, Object> payload) {
        Recipient recipient = recipientOf(payload);

        notifications.sendTransactionalEmail(recipient, "refund-completed",
                recipient.isArabic() ? "تم استرداد المبلغ" : "Refund issued",
                Map.of("orderNumber", payload.get("orderNumber"),
                        "amount", payload.get("amount"),
                        "currency", payload.get("currency")));

        userId(payload).ifPresent(userId -> notifications.notifyUser(userId,
                Notification.REFUND_COMPLETED,
                "تم استرداد المبلغ", "Refund issued",
                "تم استرداد " + payload.get("amount") + " " + payload.get("currency"),
                "We refunded " + payload.get("amount") + " " + payload.get("currency"),
                Map.of("orderNumber", payload.get("orderNumber"))));
    }

    // ---------------------------------------------------------------- helpers --

    private Recipient recipientOf(Map<String, Object> payload) {
        return new Recipient(
                userId(payload).orElse(null),
                String.valueOf(payload.getOrDefault("email", "")),
                String.valueOf(payload.getOrDefault("customerName", "")),
                String.valueOf(payload.getOrDefault("locale", "ar")));
    }

    private java.util.Optional<UUID> userId(Map<String, Object> payload) {
        Object value = payload.get("userId");
        if (value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(UUID.fromString(String.valueOf(value)));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    private String orderUrl(Map<String, Object> payload) {
        return properties.getWebUrl() + "/account/orders/" + payload.get("orderNumber");
    }
}
