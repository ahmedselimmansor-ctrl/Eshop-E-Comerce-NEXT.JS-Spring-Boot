package com.eshop.media.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.util.Hashes;
import com.eshop.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Filesystem-backed storage for local development.
 *
 * <p>Lets the whole stack run from {@code docker-compose} with no GCP project, no credentials, and
 * no network. It is deliberately not a faithful emulation - there are no real signed URLs, and
 * uploads go through the API rather than direct-to-bucket - so it is guarded by
 * {@code eshop.storage.provider=local} and can never be selected in a deployed environment, where
 * the property is pinned to {@code gcs}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "eshop.storage", name = "provider", havingValue = "local")
public class LocalStorageService implements StorageService {

    private static final Path ROOT = Paths.get(System.getProperty("java.io.tmpdir"), "eshop-media");

    private final AppProperties properties;

    @Override
    public UploadTarget createUploadUrl(String folder, String filename, String contentType) {
        if (!properties.getStorage().getAllowedContentTypes().contains(contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Content type not allowed: " + contentType);
        }

        String objectKey = buildObjectKey(folder, filename);

        return new UploadTarget(
                // The dev upload endpoint, not a signed URL - the local frontend PUTs here instead.
                properties.getBaseUrl() + "/api/v1/dev/media/" + objectKey,
                objectKey,
                publicUrl(objectKey),
                Instant.now().plus(Duration.ofMinutes(properties.getStorage().getSignedUrlTtlMinutes())),
                contentType);
    }

    @Override
    public String createDownloadUrl(String objectKey, Duration validity) {
        return publicUrl(objectKey);
    }

    @Override
    public String upload(String objectKey, byte[] content, String contentType, boolean publicRead) {
        try {
            Path target = resolve(objectKey);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return publicUrl(objectKey);

        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "Local upload failed", e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            log.warn("local_delete_failed key={} error={}", objectKey, e.getMessage());
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.exists(resolve(objectKey));
    }

    @Override
    public String publicUrl(String objectKey) {
        String base = properties.getStorage().getCdnBaseUrl();
        return base.endsWith("/") ? base + objectKey : base + "/" + objectKey;
    }

    /**
     * Resolves a key beneath the media root, refusing anything that escapes it.
     *
     * <p>The same traversal guard as production even though this only runs locally - a dev-only
     * path that writes outside its sandbox is still a path that writes outside its sandbox.
     */
    private Path resolve(String objectKey) {
        Path resolved = ROOT.resolve(objectKey).normalize();
        if (!resolved.startsWith(ROOT)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid object key");
        }
        return resolved;
    }

    private String buildObjectKey(String folder, String filename) {
        String safeFolder = folder == null || folder.isBlank()
                ? "misc"
                : folder.replaceAll("[^a-zA-Z0-9-]", "").toLowerCase(Locale.ROOT);

        String extension = filename != null && filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).replaceAll("[^a-zA-Z0-9]", "")
                : "bin";

        return safeFolder + "/" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"))
                + "/" + Hashes.randomToken(12).replaceAll("[^a-zA-Z0-9]", "") + "." + extension;
    }
}
