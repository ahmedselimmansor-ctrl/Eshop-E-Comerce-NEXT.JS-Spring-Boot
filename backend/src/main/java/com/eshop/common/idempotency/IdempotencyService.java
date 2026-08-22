package com.eshop.common.idempotency;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.util.Hashes;
import com.eshop.config.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Makes an operation safe to retry.
 *
 * <p>The problem this solves is concrete: a customer taps "Pay" on a flaky mobile connection, the
 * request reaches the server and succeeds, the response is lost, and the app retries. Without a
 * guard, they now have two orders and two payments.
 *
 * <p>The protocol:
 * <ol>
 *   <li>Insert the key with state {@code IN_PROGRESS}. Because the key is the primary key, a
 *       concurrent duplicate loses this insert rather than proceeding in parallel.</li>
 *   <li>Run the operation, then store its response and mark the key {@code COMPLETED}.</li>
 *   <li>A later request with the same key and the same body gets the stored response back.</li>
 *   <li>The same key with a <em>different</em> body is a client error - 409, not a silent replay.</li>
 * </ol>
 *
 * <p>The claim and the completion each run in their own transaction so the key survives even when
 * the business transaction rolls back - a failed attempt must not free the key for an immediate
 * retry that could race the first one's cleanup. They go through an explicit
 * {@link TransactionTemplate} rather than {@code @Transactional(REQUIRES_NEW)}: these methods are
 * called from {@link #execute} on the same bean, and a self-invocation never passes through the
 * Spring proxy, so the annotation would be silently ignored.
 */
@Slf4j
@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository keys;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final TransactionTemplate requiresNew;

    public IdempotencyService(IdempotencyKeyRepository keys,
                              ObjectMapper objectMapper,
                              AppProperties properties,
                              PlatformTransactionManager transactionManager) {
        this.keys = keys;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs {@code operation} at most once for the given key.
     *
     * @param key          the client's {@code Idempotency-Key}
     * @param endpoint     route identifier, so the same key on two endpoints does not collide
     * @param requestBody  the request, hashed to detect a key reused with different content
     * @param responseType type to deserialise a stored response back into
     * @throws BusinessException {@code IDEMPOTENCY_CONFLICT} on a key reused with a different body,
     *                           {@code IDEMPOTENCY_IN_PROGRESS} while an earlier attempt is running
     */
    public <T> T execute(String key,
                         String endpoint,
                         UUID userId,
                         Object requestBody,
                         Class<T> responseType,
                         Supplier<T> operation) {

        String requestHash = hash(requestBody);

        Optional<IdempotencyKey> existing = keys.findById(key);
        if (existing.isPresent()) {
            return replay(existing.get(), key, requestHash, responseType);
        }

        try {
            claim(key, endpoint, userId, requestHash);
        } catch (DataIntegrityViolationException raced) {
            // Another request claimed the key between the read above and this insert.
            IdempotencyKey winner = keys.findById(key)
                    .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                            "Idempotency key claimed then vanished: " + key));
            return replay(winner, key, requestHash, responseType);
        }

        T result = operation.get();
        complete(key, result);
        return result;
    }

    private <T> T replay(IdempotencyKey record, String key, String requestHash, Class<T> responseType) {
        if (!record.getRequestHash().equals(requestHash)) {
            log.warn("idempotency_key_reused_with_different_body key={}", key);
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency key reused with a different request body");
        }

        if (!record.isCompleted()) {
            // The first attempt is still running. Telling the client to retry shortly is safer than
            // executing a second time.
            throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "A request with this idempotency key is still being processed");
        }

        log.info("idempotency_replay key={} status={}", key, record.getResponseStatus());
        return objectMapper.convertValue(record.getResponseBody(), responseType);
    }

    private void claim(String key, String endpoint, UUID userId, String requestHash) {
        requiresNew.executeWithoutResult(status -> {
            IdempotencyKey record = new IdempotencyKey();
            record.setKey(key);
            record.setEndpoint(endpoint);
            record.setUserId(userId);
            record.setRequestHash(requestHash);
            record.setState(IdempotencyKey.State.IN_PROGRESS);
            record.setExpiresAt(Instant.now().plusSeconds(
                    properties.getCheckout().getIdempotencyTtlHours() * 3600L));

            // Flushed immediately so a concurrent duplicate trips the primary-key constraint here,
            // rather than at commit - after the operation has already run.
            keys.saveAndFlush(record);
        });
    }

    private void complete(String key, Object response) {
        requiresNew.executeWithoutResult(status ->
                keys.findById(key).ifPresent(record -> {
                    record.complete(200, objectMapper.valueToTree(response));
                    keys.save(record);
                }));
    }

    /**
     * Releases a key after a failed attempt so the client can retry with a corrected request.
     *
     * <p>Only safe for failures that produced no side effects - a validation rejection, an
     * out-of-stock check. A failure <em>after</em> an order was created must keep the key.
     */
    public void release(String key) {
        requiresNew.executeWithoutResult(status -> keys.deleteById(key));
    }

    @Transactional
    public int purgeExpired() {
        return keys.deleteExpired(Instant.now());
    }

    /**
     * Canonical hash of the request.
     *
     * <p>Jackson serialises record components in declaration order, so the same logical request
     * hashes identically across retries from the same client build.
     */
    private String hash(Object requestBody) {
        try {
            return Hashes.sha256Hex(objectMapper.writeValueAsString(requestBody));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to hash the request body for idempotency", e);
        }
    }
}
