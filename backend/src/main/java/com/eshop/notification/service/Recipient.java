package com.eshop.notification.service;

import java.util.UUID;

/**
 * Who a notification is for, as a value rather than an entity.
 *
 * <p>Passing a {@code User} across the domain boundary would let the notification code navigate
 * into orders, addresses, and roles — and would tie it to a JPA session it has no business holding
 * while sending an email. Four fields is everything a template needs.
 *
 * @param userId {@code null} for a guest (order confirmations sent to a checkout email)
 * @param locale {@code ar} or {@code en}; selects the template variant
 */
public record Recipient(UUID userId, String email, String name, String locale) {

    public static Recipient guest(String email, String name, String locale) {
        return new Recipient(null, email, name, locale == null ? "ar" : locale);
    }

    public boolean isRegistered() {
        return userId != null;
    }

    public boolean isArabic() {
        return !"en".equalsIgnoreCase(locale);
    }
}
