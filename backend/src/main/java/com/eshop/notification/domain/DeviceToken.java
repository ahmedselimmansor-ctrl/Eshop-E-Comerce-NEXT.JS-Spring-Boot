package com.eshop.notification.domain;

import com.eshop.common.domain.BaseEntity;
import com.eshop.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A registered device for push notifications.
 *
 * <p>One user has many: a phone, a tablet, a browser. Tokens rotate whenever an app is reinstalled
 * or its data cleared, so stale rows accumulate — {@code isActive} is cleared when Firebase reports
 * a token as unregistered, which is the only reliable signal that a device is gone.
 */
@Entity
@Table(name = "device_tokens")
@Getter
@Setter
@NoArgsConstructor
public class DeviceToken extends BaseEntity {

    public enum Platform {
        IOS,
        ANDROID,
        WEB
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The FCM registration token. Unique across the table — a device belongs to one account. */
    @Column(name = "token", nullable = false, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 10)
    private Platform platform;

    /** Language this device should receive pushes in; may differ from the account default. */
    @Column(name = "locale", nullable = false, length = 5)
    private String locale = "ar";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    public void touch() {
        lastSeenAt = Instant.now();
        active = true;
    }

    /** Called when FCM reports the token as unregistered — the app was uninstalled. */
    public void deactivate() {
        active = false;
    }
}
