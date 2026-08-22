package com.eshop.media.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.util.Hashes;
import com.eshop.config.AppProperties;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Google Cloud Storage implementation.
 *
 * <p>Authentication is via Workload Identity - the pod's Kubernetes service account is bound to a
 * Google service account, and the client picks up credentials from the metadata server. No service
 * account JSON key exists anywhere in the image, the repository, or a secret.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "eshop.storage", name = "provider", havingValue = "gcs", matchIfMissing = true)
public class GcsStorageService implements StorageService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "avif");

    private final Storage storage;
    private final AppProperties properties;

    private AppProperties.Storage config() {
        return properties.getStorage();
    }

    @Override
    public UploadTarget createUploadUrl(String folder, String filename, String contentType) {
        assertAllowedContentType(contentType);

        String objectKey = buildObjectKey(folder, filename, contentType);
        Duration validity = Duration.ofMinutes(config().getSignedUrlTtlMinutes());

        BlobInfo blob = BlobInfo.newBuilder(BlobId.of(config().getMediaBucket(), objectKey))
                .setContentType(contentType)
                .build();

        // The content type is signed in, so an upload that declares a different type is rejected by
        // GCS itself. Without that, a caller could request a URL for image/png and upload HTML,
        // which would then be served from our own domain.
        URL signed = storage.signUrl(blob, validity.toMinutes(), TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(com.google.cloud.storage.HttpMethod.PUT),
                Storage.SignUrlOption.withContentType(),
                Storage.SignUrlOption.withV4Signature());

        return new UploadTarget(
                signed.toString(),
                objectKey,
                publicUrl(objectKey),
                Instant.now().plus(validity),
                contentType);
    }

    @Override
    public String createDownloadUrl(String objectKey, Duration validity) {
        BlobInfo blob = BlobInfo.newBuilder(BlobId.of(config().getPrivateBucket(), objectKey)).build();

        return storage.signUrl(blob, validity.toMinutes(), TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(com.google.cloud.storage.HttpMethod.GET),
                Storage.SignUrlOption.withV4Signature()).toString();
    }

    @Override
    public String upload(String objectKey, byte[] content, String contentType, boolean publicRead) {
        String bucket = publicRead ? config().getMediaBucket() : config().getPrivateBucket();

        BlobInfo blob = BlobInfo.newBuilder(BlobId.of(bucket, objectKey))
                .setContentType(contentType)
                // Immutable objects: keys are content-addressed, so an object at a given key never
                // changes and can be cached at the edge for a year.
                .setCacheControl(publicRead ? "public, max-age=31536000, immutable" : "private, no-store")
                .build();

        try {
            storage.create(blob, content);
        } catch (RuntimeException e) {
            log.error("gcs_upload_failed key={} error={}", objectKey, e.toString());
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "Upload failed", e);
        }

        return publicRead ? publicUrl(objectKey) : objectKey;
    }

    @Override
    public void delete(String objectKey) {
        try {
            storage.delete(BlobId.of(config().getMediaBucket(), objectKey));
        } catch (RuntimeException e) {
            // Logged, not thrown: a failed cleanup leaves an orphaned object, which a bucket
            // lifecycle rule will collect. Failing the caller's request over it would be worse.
            log.warn("gcs_delete_failed key={} error={}", objectKey, e.toString());
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            return storage.get(BlobId.of(config().getMediaBucket(), objectKey)) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String publicUrl(String objectKey) {
        String base = config().getCdnBaseUrl();
        return base.endsWith("/") ? base + objectKey : base + "/" + objectKey;
    }

    /**
     * Builds a collision-free, date-partitioned key.
     *
     * <p>The client's filename is never used as the key. A caller-supplied path is a directory
     * traversal waiting to happen, and two merchandisers uploading {@code image.jpg} would
     * overwrite each other. Only the extension is taken, and it is validated against the declared
     * content type rather than trusted.
     */
    private String buildObjectKey(String folder, String filename, String contentType) {
        String safeFolder = folder == null || folder.isBlank()
                ? "misc"
                : folder.replaceAll("[^a-zA-Z0-9-]", "").toLowerCase(Locale.ROOT);

        String extension = extensionFor(filename, contentType);
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String random = Hashes.randomToken(12).replaceAll("[^a-zA-Z0-9]", "");

        return safeFolder + "/" + datePath + "/" + random + "." + extension;
    }

    private String extensionFor(String filename, String contentType) {
        String fromType = switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/avif" -> "avif";
            case "application/pdf" -> "pdf";
            case "text/csv" -> "csv";
            default -> null;
        };
        if (fromType != null) {
            return fromType;
        }

        if (filename != null && filename.contains(".")) {
            String candidate = filename.substring(filename.lastIndexOf('.') + 1)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
            if (IMAGE_EXTENSIONS.contains(candidate)) {
                return candidate;
            }
        }
        return "bin";
    }

    private void assertAllowedContentType(String contentType) {
        if (contentType == null || !config().getAllowedContentTypes().contains(contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Content type not allowed: " + contentType)
                    .withViolation("contentType", "NOT_ALLOWED",
                            "نوع الملف غير مدعوم", contentType);
        }
    }
}
