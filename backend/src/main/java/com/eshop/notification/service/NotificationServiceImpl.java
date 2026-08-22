package com.eshop.notification.service;

import com.eshop.config.AppProperties;
import com.eshop.notification.domain.Notification;
import com.eshop.notification.repository.DeviceTokenRepository;
import com.eshop.notification.repository.NotificationRepository;
import com.eshop.user.repository.UserRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Sends email, push, and in-app notifications.
 *
 * <p>Everything here is {@code @Async} and swallows its own failures. A mail server that is briefly
 * unreachable must never fail a checkout that has already taken payment — the customer's money is
 * gone and their order is real whether or not the confirmation email lands.
 *
 * <p>Delivery-critical messages are therefore not sent by a direct call from the checkout path.
 * They are driven off {@code outbox_events}, so an email that fails is retried by the relay instead
 * of being silently lost when the pod restarts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notifications;
    private final DeviceTokenRepository deviceTokens;
    private final UserRepository users;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final PushSender pushSender;
    private final AppProperties properties;

    // ----------------------------------------------------------------- email --

    @Override
    @Async
    public void sendEmailVerification(Recipient recipient, String token) {
        String link = properties.getWebUrl() + "/auth/verify-email?token=" + token;

        sendTemplate(recipient, "verify-email",
                recipient.isArabic() ? "تفعيل بريدك الإلكتروني" : "Verify your email address",
                Map.of("name", recipient.name(), "verificationUrl", link, "expiryHours", 24));
    }

    @Override
    @Async
    public void sendPasswordReset(Recipient recipient, String token) {
        String link = properties.getWebUrl() + "/auth/reset-password?token=" + token;

        sendTemplate(recipient, "reset-password",
                recipient.isArabic() ? "إعادة تعيين كلمة المرور" : "Reset your password",
                Map.of("name", recipient.name(), "resetUrl", link, "expiryMinutes", 30));
    }

    @Override
    @Async
    public void sendWelcome(Recipient recipient) {
        sendTemplate(recipient, "welcome",
                recipient.isArabic() ? "أهلاً بك في إي‑شوب" : "Welcome to Eshop",
                Map.of("name", recipient.name(), "shopUrl", properties.getWebUrl()));
    }

    /**
     * Tells the account owner that something security-relevant happened.
     *
     * <p>Sent after a password change or a session-wide revocation. If the owner did not do it,
     * this email is how they find out — which is the entire point of sending it even though the
     * action has already completed.
     */
    @Override
    @Async
    public void sendSecurityAlert(Recipient recipient, String eventKey, Map<String, Object> context) {
        Map<String, Object> model = new HashMap<>(context);
        model.put("name", recipient.name());
        model.put("event", eventKey);
        model.put("supportUrl", properties.getWebUrl() + "/support");

        sendTemplate(recipient, "security-alert",
                recipient.isArabic() ? "تنبيه أمني على حسابك" : "Security alert on your account",
                model);

        if (recipient.isRegistered()) {
            notifyUser(recipient.userId(), Notification.SECURITY_ALERT,
                    "تنبيه أمني", "Security alert",
                    "تم تغيير إعدادات الأمان في حسابك", "Your account security settings changed",
                    context);
        }
    }

    @Override
    @Async
    public void sendTransactionalEmail(Recipient recipient, String templateKey,
                                       String subject, Map<String, Object> model) {
        sendTemplate(recipient, templateKey, subject, model);
    }

    /**
     * Renders and sends one email.
     *
     * <p>Template selection is by locale suffix ({@code order-confirmation-ar.html}), and the
     * rendered HTML sets {@code dir="rtl"} for Arabic — email clients do not infer direction, and an
     * Arabic email laid out left-to-right is close to unreadable.
     */
    private void sendTemplate(Recipient recipient, String templateKey,
                              String subject, Map<String, Object> model) {
        if (recipient.email() == null || recipient.email().isBlank()) {
            return;
        }

        try {
            Context context = new Context(Locale.forLanguageTag(recipient.locale()));
            context.setVariables(model);
            context.setVariable("locale", recipient.locale());
            context.setVariable("direction", recipient.isArabic() ? "rtl" : "ltr");
            context.setVariable("storeName", recipient.isArabic() ? "إي‑شوب" : "Eshop");
            context.setVariable("webUrl", properties.getWebUrl());
            context.setVariable("year", java.time.Year.now().getValue());

            String html = templateEngine.process(
                    "email/" + templateKey + "-" + recipient.locale(), context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getNotification().getFromEmail(),
                    properties.getNotification().getFromName());
            helper.setTo(recipient.email());
            helper.setSubject(subject);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("email_sent template={} locale={}", templateKey, recipient.locale());

        } catch (Exception e) {
            // Never rethrown. The address is logged but not the body — an order confirmation
            // contains a delivery address.
            log.error("email_send_failed template={} error={}", templateKey, e.toString());
        }
    }

    // -------------------------------------------------------- in-app + push --

    @Override
    @Async
    @Transactional
    public void notifyUser(UUID userId, String type, String titleAr, String titleEn,
                           String bodyAr, String bodyEn, Map<String, Object> data) {
        try {
            users.findActiveById(userId).ifPresent(user -> {
                Notification notification = new Notification();
                notification.setUser(user);
                notification.setType(type);
                notification.setTitleAr(titleAr);
                notification.setTitleEn(titleEn);
                notification.setBodyAr(bodyAr);
                notification.setBodyEn(bodyEn);
                notification.setData(data == null ? Map.of() : data);
                notification.setChannel(Notification.Channel.IN_APP);
                notifications.save(notification);
            });

            pushToUser(userId, titleAr, titleEn, bodyAr, bodyEn, data);

        } catch (RuntimeException e) {
            log.error("notification_create_failed user={} type={} error={}", userId, type, e.toString());
        }
    }

    @Override
    @Async
    @Transactional(readOnly = true)
    public void pushToUser(UUID userId, String titleAr, String titleEn,
                           String bodyAr, String bodyEn, Map<String, Object> data) {

        var devices = deviceTokens.findActiveForUser(userId);
        if (devices.isEmpty()) {
            return;
        }

        // Grouped by the device's own language, not the account's — a shared tablet set to English
        // should get English even if the account default is Arabic.
        devices.forEach(device -> {
            boolean arabic = !"en".equalsIgnoreCase(device.getLocale());
            pushSender.send(device,
                    arabic ? titleAr : (titleEn == null ? titleAr : titleEn),
                    arabic ? bodyAr : (bodyEn == null ? bodyAr : bodyEn),
                    data == null ? Map.of() : data);
        });
    }
}
