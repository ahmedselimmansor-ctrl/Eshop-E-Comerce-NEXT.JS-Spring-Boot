package com.eshop.common.domain;

import com.eshop.common.Uuid7;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.proxy.HibernateProxy;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Root of every persistent type: a client-generated {@link Uuid7} primary key plus a creation
 * timestamp.
 *
 * <p>The ID is assigned in {@link PrePersist} rather than by the database. That keeps the value
 * available before the flush (useful when building an outbox event that references the aggregate
 * being written in the same transaction) while still leaving {@code id == null} for new instances,
 * which is what Spring Data uses to choose {@code persist} over {@code merge}.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity implements Serializable {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onPersist() {
        if (id == null) {
            id = Uuid7.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Identity is the primary key alone. Two managed instances of the same row are equal even if
     * one is a lazy proxy; two transient instances are only equal if they are the same object.
     */
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        Class<?> thisType = effectiveClass(this);
        Class<?> otherType = effectiveClass(other);
        if (!thisType.equals(otherType)) {
            return false;
        }
        UUID thisId = this.getId();
        UUID otherId = ((BaseEntity) other).getId();
        return thisId != null && thisId.equals(otherId);
    }

    @Override
    public final int hashCode() {
        // Constant per type: the ID is null before persist, and a hash that changes on flush would
        // corrupt any HashSet the entity had already been added to.
        return effectiveClass(this).hashCode();
    }

    private static Class<?> effectiveClass(Object candidate) {
        return candidate instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : candidate.getClass();
    }

    @Override
    public String toString() {
        return effectiveClass(this).getSimpleName() + "(" + id + ")";
    }
}
