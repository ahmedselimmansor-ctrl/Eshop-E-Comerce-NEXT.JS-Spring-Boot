package com.eshop.notification.service;

import com.eshop.config.AppProperties;
import com.eshop.notification.domain.DeviceToken;
import com.eshop.notification.repository.DeviceTokenRepository;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Firebase Cloud Messaging delivery.
 *
 * <p>Disabled unless {@code eshop.notification.fcm.enabled} is set, so local development and CI
 * never attempt to reach Firebase. When disabled, {@link #send} logs and returns - callers do not
 * branch on availability.
 *
 * <p>Tokens that Firebase reports as unregistered are deactivated immediately. Continuing to push
 * to dead tokens wastes quota and, at scale, gets a sender throttled.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushSender {

    private final AppProperties properties;
    private final DeviceTokenRepository deviceTokens;

    private volatile FirebaseMessaging messaging;

    @PostConstruct
    void initialise() {
        AppProperties.Notification.Fcm config = properties.getNotification().getFcm();

        if (!config.isEnabled()) {
            log.info("fcm_disabled - push notifications will be skipped");
            return;
        }

        try {
            GoogleCredentials credentials = config.getCredentialsJson().isBlank()
                    // On GKE with Workload Identity there is no key file; the metadata server
                    // supplies credentials.
                    ? GoogleCredentials.getApplicationDefault()
                    : GoogleCredentials.fromStream(new ByteArrayInputStream(
                            config.getCredentialsJson().getBytes(StandardCharsets.UTF_8)));

            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(FirebaseOptions.builder()
                            .setCredentials(credentials)
                            .build())
                    : FirebaseApp.getInstance();

            messaging = FirebaseMessaging.getInstance(app);
            log.info("fcm_initialised");

        } catch (Exception e) {
            // Never fatal: push is a convenience, and a store that cannot boot because Firebase is
            // misconfigured is a worse outcome than a store with no push notifications.
            log.error("fcm_init_failed error={} - push notifications disabled", e.toString());
        }
    }

    public void send(DeviceToken device, String title, String body, Map<String, Object> data) {
        if (messaging == null) {
            log.debug("push_skipped_fcm_unavailable token={}", abbreviate(device.getToken()));
            return;
        }

        try {
            Message message = Message.builder()
                    .setToken(device.getToken())
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    // Data values must be strings; anything else is rejected at send time.
                    .putAllData(stringify(data))
                    .setAndroidConfig(AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setTtl(java.time.Duration.ofHours(24).toMillis())
                            .build())
                    .setApnsConfig(ApnsConfig.builder()
                            .setAps(Aps.builder().setContentAvailable(true).build())
                            .build())
                    .build();

            messaging.send(message);
            log.debug("push_sent platform={} type={}", device.getPlatform(), data.get("type"));

        } catch (FirebaseMessagingException e) {
            handleFailure(device, e);
        } catch (RuntimeException e) {
            log.warn("push_send_failed token={} error={}", abbreviate(device.getToken()), e.toString());
        }
    }

    /**
     * Retires a token Firebase has told us is dead.
     *
     * <p>{@code UNREGISTERED} means the app was uninstalled or its data cleared;
     * {@code INVALID_ARGUMENT} means the token is malformed. Both are permanent, so the row is
     * deactivated rather than retried. Anything else is transient and left alone.
     */
    private void handleFailure(DeviceToken device, FirebaseMessagingException e) {
        MessagingErrorCode code = e.getMessagingErrorCode();

        if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
            deviceTokens.deactivateAll(List.of(device.getToken()));
            log.info("device_token_deactivated platform={} reason={}", device.getPlatform(), code);
        } else {
            log.warn("push_send_failed platform={} code={}", device.getPlatform(), code);
        }
    }

    private static Map<String, String> stringify(Map<String, Object> data) {
        Map<String, String> stringified = new java.util.LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (value != null) {
                stringified.put(key, String.valueOf(value));
            }
        });
        return stringified;
    }

    /** Tokens are credentials of a sort; only a prefix ever reaches the logs. */
    private static String abbreviate(String token) {
        return token == null || token.length() < 12 ? "***" : token.substring(0, 8) + "…";
    }
}
