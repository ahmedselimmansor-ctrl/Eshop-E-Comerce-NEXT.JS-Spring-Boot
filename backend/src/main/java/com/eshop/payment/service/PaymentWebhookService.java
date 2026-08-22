package com.eshop.payment.service;

import com.eshop.payment.domain.Payment;
import com.eshop.payment.domain.PaymentProviderType;
import com.eshop.payment.domain.PaymentWebhookEvent;
import com.eshop.payment.repository.PaymentRepository;
import com.eshop.payment.repository.PaymentWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Receives and applies payment provider callbacks.
 *
 * <p>The order of operations is the security and correctness boundary:
 *
 * <ol>
 *   <li><b>Parse and verify the signature.</b> An unverified callback is never acted on - anyone
 *       can POST to a webhook URL, and "payment captured" is the most valuable message an attacker
 *       could forge.</li>
 *   <li><b>Persist the event first.</b> The unique {@code (provider, event_id)} constraint turns a
 *       duplicate delivery into a constraint violation rather than a second capture. Providers
 *       retry aggressively, and processing twice would commit stock twice and email twice.</li>
 *   <li><b>Then apply it.</b> Capture and failure handling are themselves idempotent, so even a
 *       replay that somehow got past the constraint is harmless.</li>
 * </ol>
 *
 * <p>The HTTP layer always answers {@code 200} once the event is stored, even for a signature
 * failure or a processing error. A non-2xx makes the provider retry, and retrying a callback whose
 * payload we could not verify achieves nothing except more traffic. Failures are recorded on the
 * row and retried by a scheduled job on our own terms.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    private final PaymentService payments;
    private final PaymentRepository paymentRepository;
    private final PaymentWebhookEventRepository events;

    /**
     * @return an outcome for logging and metrics; the controller answers 200 regardless
     */
    @Transactional
    public Outcome handle(PaymentProviderType providerType, String rawBody, Map<String, String> headers) {
        PaymentProvider provider = payments.require(providerType);
        PaymentProvider.Callback callback = provider.parseCallback(rawBody, headers);

        PaymentWebhookEvent event = new PaymentWebhookEvent();
        event.setProvider(providerType);
        event.setEventId(callback.eventId() == null ? "unknown" : callback.eventId());
        event.setEventType(callback.eventType());
        event.setPayload(callback.payload());
        event.setSignatureValid(callback.valid());

        try {
            events.saveAndFlush(event);
        } catch (DataIntegrityViolationException duplicate) {
            // Already seen. This is the common case under provider retries, not an error.
            log.info("webhook_duplicate_ignored provider={} event={}", providerType, event.getEventId());
            return Outcome.DUPLICATE;
        }

        if (!callback.valid()) {
            // Stored for investigation, never acted on.
            log.warn("webhook_signature_invalid provider={} event={}", providerType, event.getEventId());
            event.recordFailure("Signature verification failed");
            events.save(event);
            return Outcome.INVALID_SIGNATURE;
        }

        try {
            apply(providerType, callback);
            event.markProcessed();
            events.save(event);
            return Outcome.PROCESSED;

        } catch (RuntimeException e) {
            // Recorded rather than rethrown: the event is safely stored, and the retry job will
            // pick it up. Failing the HTTP response would only trigger a provider retry of a
            // payload we already hold.
            log.error("webhook_processing_failed provider={} event={} error={}",
                    providerType, event.getEventId(), e.toString(), e);
            event.recordFailure(e.getMessage());
            events.save(event);
            return Outcome.FAILED;
        }
    }

    private void apply(PaymentProviderType providerType, PaymentProvider.Callback callback) {
        Optional<Payment> match = paymentRepository
                .findByProviderReference(providerType, callback.reference());

        if (match.isEmpty()) {
            // Fawry quotes our order number as the merchant reference, so fall back to the
            // reference code the payment was created with.
            match = paymentRepository.findByReferenceCode(callback.reference());
        }

        Payment payment = match.orElseThrow(() -> new IllegalStateException(
                "No payment matches " + providerType + " reference " + callback.reference()));

        switch (callback.status()) {
            case CAPTURED -> payments.capture(payment.getId(), callback.reference(),
                    callback.cardBrand(), callback.cardLast4(), callback.payload());

            case FAILED -> payments.markFailed(payment.getId(), callback.failureCode(),
                    callback.failureReason(), callback.payload());

            case EXPIRED, CANCELLED -> payments.markFailed(payment.getId(),
                    callback.status().name(), "Payment " + callback.status().name().toLowerCase(),
                    callback.payload());

            case PENDING, AUTHORIZED ->
                // Fawry sends a NEW/UNPAID callback when the reference is issued. Nothing to do -
                // the customer has not paid yet, and treating this as a failure would cancel an
                // order that is proceeding normally.
                    log.debug("webhook_non_terminal provider={} status={} payment={}",
                            providerType, callback.status(), payment.getId());

            default -> log.warn("webhook_unknown_status provider={} status={} payment={}",
                    providerType, callback.status(), payment.getId());
        }
    }

    /**
     * Retries callbacks that were stored but whose processing failed - a transient database error,
     * or a deploy that landed mid-request.
     */
    @Transactional
    public int retryFailed(int batchSize, int maxAttempts) {
        var pending = events.findUnprocessed(maxAttempts,
                org.springframework.data.domain.PageRequest.of(0, batchSize));

        int recovered = 0;
        for (PaymentWebhookEvent event : pending) {
            try {
                PaymentProvider provider = payments.require(event.getProvider());
                PaymentProvider.Callback callback = provider.parseCallback(
                        toJson(event.getPayload()), Map.of());

                if (callback.valid()) {
                    apply(event.getProvider(), callback);
                    event.markProcessed();
                    recovered++;
                }
            } catch (RuntimeException e) {
                event.recordFailure(e.getMessage());
            }
            events.save(event);
        }

        if (recovered > 0) {
            log.info("webhook_retry_recovered count={}", recovered);
        }
        return recovered;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Stored webhook payload is not serialisable", e);
        }
    }

    public enum Outcome {
        PROCESSED,
        /** Already seen - the idempotency constraint did its job. */
        DUPLICATE,
        INVALID_SIGNATURE,
        /** Stored; will be retried by the scheduled job. */
        FAILED
    }
}
