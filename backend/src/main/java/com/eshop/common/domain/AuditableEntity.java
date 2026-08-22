package com.eshop.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Adds a mutation timestamp for tables that are updated in place.
 *
 * <p>PostgreSQL also maintains {@code updated_at} via the {@code touch_updated_at()} trigger from
 * migration V9, which covers writes that bypass JPA (admin SQL, batch jobs). Setting it here as
 * well keeps the in-memory entity consistent with the row after a flush without a re-read.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AuditableEntity extends BaseEntity {

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    protected void onPersist() {
        super.onPersist();
        if (updatedAt == null) {
            updatedAt = getCreatedAt();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
