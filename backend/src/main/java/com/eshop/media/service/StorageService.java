package com.eshop.media.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Object storage for product images, invoices, and data exports.
 *
 * <p>The important design choice here is that <b>uploads never pass through the API</b>. The client
 * asks for a signed URL and sends the bytes straight to Cloud Storage. Proxying a 10 MB image
 * through the application would consume a request thread and a pod's memory for the whole upload,
 * and image uploads are bursty — a merchandiser adding a product uploads a dozen at once.
 *
 * <p>Signed URLs are scoped: one object key, one HTTP method, one content type, and a short expiry.
 * A leaked URL grants exactly one upload of one file to one path, and only for the next few minutes.
 */
public interface StorageService {

    /**
     * Mints a URL the client can {@code PUT} one object to.
     *
     * @param folder      logical prefix, e.g. {@code products} or {@code reviews}
     * @param filename    the original name, used only to derive an extension
     * @param contentType must be on the allow-list; also pinned into the signature, so the upload
     *                    is rejected if the client sends a different type
     */
    UploadTarget createUploadUrl(String folder, String filename, String contentType);

    /**
     * Time-limited read URL for a private object — an invoice PDF, a data export.
     *
     * <p>Private buckets are not served through the CDN, so a link has to be signed each time
     * rather than handed out permanently.
     */
    String createDownloadUrl(String objectKey, Duration validity);

    /** Uploads bytes the server itself generated: a rendered invoice, a CSV export. */
    String upload(String objectKey, byte[] content, String contentType, boolean publicRead);

    /** Removes an object. Missing objects are not an error — deletion is idempotent. */
    void delete(String objectKey);

    boolean exists(String objectKey);

    /**
     * The public URL for an object, served through the CDN.
     *
     * <p>Built from the configured CDN base rather than the bucket's own URL so that images are
     * served from the edge and the bucket origin is never exposed in markup.
     */
    String publicUrl(String objectKey);

    /**
     * @param uploadUrl the signed {@code PUT} target
     * @param objectKey the key the client must not alter — the signature is bound to it
     * @param publicUrl where the object will be readable once uploaded
     * @param expiresAt when the signed URL stops working
     * @param requiredContentType the exact {@code Content-Type} header the client must send
     */
    record UploadTarget(String uploadUrl,
                        String objectKey,
                        String publicUrl,
                        Instant expiresAt,
                        String requiredContentType) {
    }
}
