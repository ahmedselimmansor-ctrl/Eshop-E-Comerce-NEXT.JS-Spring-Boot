package com.eshop.media.web;

import com.eshop.media.service.StorageService;
import com.eshop.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Media uploads, via signed URLs.
 *
 * <p>The API never receives file bytes. The client asks here for a signed target, uploads directly
 * to Cloud Storage, and then sends back the returned {@code publicUrl} when saving the product.
 * That keeps request threads free during a burst of image uploads and keeps large bodies out of
 * the application entirely.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/media")
@RequiredArgsConstructor
@Tag(name = "Admin · Media")
public class MediaController {

    private final StorageService storage;

    @PostMapping("/upload-url")
    @PreAuthorize("hasAuthority('media:write')")
    @Operation(summary = "Request a signed upload URL",
            description = """
                    Returns a URL valid for a single PUT of a single object, for about fifteen
                    minutes. The `Content-Type` header on the upload must match
                    `requiredContentType` exactly - it is part of the signature, so a mismatched
                    upload is rejected by Cloud Storage rather than by us.

                    The object key is generated server-side. A client-supplied path would be a
                    directory traversal, and two uploads named `image.jpg` would overwrite one
                    another.
                    """)
    public StorageService.UploadTarget createUploadUrl(
            @AuthenticationPrincipal UserPrincipal caller,
            @Valid @RequestBody UploadUrlRequest request) {

        StorageService.UploadTarget target = storage.createUploadUrl(
                request.folder(), request.filename(), request.contentType());

        log.info("upload_url_issued actor={} folder={} key={}",
                caller.id(), request.folder(), target.objectKey());

        return target;
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('media:write')")
    @Operation(summary = "Delete an object",
            description = "Idempotent - deleting a key that does not exist succeeds.")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal caller,
                                       @Valid @RequestBody DeleteRequest request) {
        storage.delete(request.objectKey());
        log.info("media_deleted actor={} key={}", caller.id(), request.objectKey());
        return ResponseEntity.noContent().build();
    }

    public record UploadUrlRequest(
            @NotBlank @Size(max = 255) String filename,

            @NotBlank
            @Pattern(regexp = "^(image/(jpeg|png|webp|avif)|application/pdf)$",
                    message = "نوع الملف غير مدعوم")
            String contentType,

            @Pattern(regexp = "^[a-z0-9-]{1,40}$")
            String folder
    ) {
    }

    public record DeleteRequest(@NotBlank @Size(max = 512) String objectKey) {
    }
}
