package com.eshop.notification.service;

import java.util.Map;
import java.util.UUID;

/**
 * The only entry point other domains use to reach the notification system.
 *
 * <p>Every method is fire-and-forget from the caller's perspective: a mail server that is briefly
 * unreachable must never fail a checkout that has already taken payment. Delivery-critical
 * notifications (order confirmation, payment receipt) are driven off {@code outbox_events} instead
 * of a direct call, so they survive a crash between the database commit and the send.
 */
public interface NotificationService {

    /** Link-based email verification, sent at registration. */
    void sendEmailVerification(Recipient recipient, String token);

    /** Password reset link. Sent even for unknown addresses is <em>not</em> the caller's concern -
     *  {@code AuthService} decides that, so this is only ever called for a real account. */
    void sendPasswordReset(Recipient recipient, String token);

    void sendWelcome(Recipient recipient);

    /** Confirms a change of password or a session-wide revocation, so the owner notices a takeover. */
    void sendSecurityAlert(Recipient recipient, String eventKey, Map<String, Object> context);

    /**
     * Generic transactional email rendered from a Thymeleaf template.
     *
     * @param templateKey template name without locale suffix, e.g. {@code order-confirmation}
     * @param model       template variables
     */
    void sendTransactionalEmail(Recipient recipient, String templateKey, String subjectKey,
                                Map<String, Object> model);

    /** In-app notification row plus, when the user has registered devices, an FCM push. */
    void notifyUser(UUID userId, String type, String titleAr, String titleEn,
                    String bodyAr, String bodyEn, Map<String, Object> data);

    /** Push only, for events with no in-app inbox counterpart (e.g. abandoned-cart nudges). */
    void pushToUser(UUID userId, String titleAr, String titleEn, String bodyAr, String bodyEn,
                    Map<String, Object> data);
}
