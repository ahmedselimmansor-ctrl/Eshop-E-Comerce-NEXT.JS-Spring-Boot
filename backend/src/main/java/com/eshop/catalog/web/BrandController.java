package com.eshop.catalog.web;

import com.eshop.catalog.service.BrandService;
import com.eshop.catalog.web.dto.BrandResponse;
import com.eshop.common.web.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/brands")
@RequiredArgsConstructor
@Tag(name = "Catalog")
@SecurityRequirements
public class BrandController {

    private static final CacheControl PUBLIC_CACHE = CacheControl
            .maxAge(Duration.ofMinutes(5))
            .cachePublic()
            .staleWhileRevalidate(Duration.ofMinutes(15));

    private final BrandService brands;

    @GetMapping
    @Operation(summary = "List brands")
    public ResponseEntity<List<BrandResponse>> list(
            @RequestParam(required = false, defaultValue = "false") boolean featured) {

        String locale = RequestContext.locale();
        List<BrandResponse> body = BrandResponse.from(
                featured ? brands.listFeatured() : brands.listActive(), locale);

        return ResponseEntity.ok().cacheControl(PUBLIC_CACHE).body(body);
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Brand by slug")
    public ResponseEntity<BrandResponse> bySlug(@PathVariable String slug) {
        BrandResponse body = BrandResponse.from(brands.requireBySlug(slug), RequestContext.locale());
        return ResponseEntity.ok().cacheControl(PUBLIC_CACHE).body(body);
    }
}
