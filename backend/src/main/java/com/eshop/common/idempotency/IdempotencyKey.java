package com.eshop.common.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A record of a side-effecting request that must not be executed twice.
 *
 * <p>The primary key is the client-supplied {@code Idempotency-Key} header, so a duplicate is
 * caught by a unique-constraint violation rather than by a check-then-act read that two concurrent
 * requests could both pass.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyKey {

    public enum State {
        /** A request holding this key is executing right now. */
        IN_PROGRESS,
        /** Finished; {@code responseStatus} and {@code responseBody} are the stored result. */
        COMPLETED
    }

    @Id
    @Column(name = "key", nullable = false, length = 80)
    private String key;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "endpoint", nullable = false, length = 120)
    private String endpoint;

    /**
     * SHA-256 of the canonical request body.
     *
     * <p>Reusing a key with a <em>different</em> body is a client bug, not a retry — replaying the
     * first response would silently discard the second request. That case returns 409.
     */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Short responseStatus;

    /**
     * The first attempt's response, replayed verbatim on a retry.
     *
     * <p>Typed as {@link JsonNode} rather than {@code Object}: Hibernate cannot infer a JSON
     * serialiser for a bare {@code Object} field and falls back to treating it as a {@code String},
     * which throws {@code ClassCastException} at commit for every response that is not one — that is,
     * all of them. A concrete node type gives the Jackson format mapper something to work with.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private JsonNode responseBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private State state = State.IN_PROGRESS;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public void complete(int status, JsonNode body) {
        this.responseStatus = (short) status;
        this.responseBody = body;
        this.state = State.COMPLETED;
    }
}
