package com.eshop.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records domain events for later publication.
 *
 * <p>{@code MANDATORY} propagation is deliberate and load-bearing: an event must only ever be
 * written inside the transaction that made the change it describes. Calling this without an active
 * transaction fails loudly rather than quietly creating an event that could outlive a rolled-back
 * order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository outbox;

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(String aggregateType, UUID aggregateId,
                              String eventType, Map<String, Object> payload) {
        OutboxEvent event = outbox.save(OutboxEvent.of(aggregateType, aggregateId, eventType, payload));
        log.debug("outbox_event_recorded type={} aggregate={}", eventType, aggregateId);
        return event;
    }

    /** Convenience for the common shape: an aggregate id plus a handful of scalar fields. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(String aggregateType, UUID aggregateId, String eventType,
                              Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Payload arguments must be key/value pairs");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            payload.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return record(aggregateType, aggregateId, eventType, payload);
    }
}
