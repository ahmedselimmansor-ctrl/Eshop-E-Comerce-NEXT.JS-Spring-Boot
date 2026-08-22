package com.eshop.catalog.web;

import com.eshop.catalog.domain.Attribute;
import com.eshop.catalog.service.AttributeService;
import com.eshop.common.web.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attributes")
@RequiredArgsConstructor
@Tag(name = "Catalog")
@SecurityRequirements
public class AttributeController {

    private final AttributeService attributes;

    @GetMapping
    @Operation(summary = "Attribute definitions",
            description = "Used to render the filter sidebar and the variant selector. "
                    + "Counts are not included here - those come from the listing response's facets.")
    public ResponseEntity<List<AttributeResponse>> list(
            @RequestParam(defaultValue = "true") boolean filterable) {

        String locale = RequestContext.locale();
        List<Attribute> source = filterable ? attributes.filterable() : attributes.all();

        List<AttributeResponse> body = source.stream()
                .map(attribute -> AttributeResponse.from(attribute, locale))
                .toList();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))
                        .cachePublic()
                        .staleWhileRevalidate(Duration.ofMinutes(15)))
                .body(body);
    }

    @Schema(name = "AttributeResponse")
    public record AttributeResponse(UUID id, String code, String name, String type,
                                    boolean variantAxis, boolean filterable, String unit,
                                    List<ValueResponse> values) {

        public static AttributeResponse from(Attribute attribute, String locale) {
            return new AttributeResponse(
                    attribute.getId(),
                    attribute.getCode(),
                    attribute.name(locale),
                    attribute.getType().name(),
                    attribute.isVariantAxis(),
                    attribute.isFilterable(),
                    attribute.getUnit(),
                    attribute.getValues().stream()
                            .map(value -> new ValueResponse(
                                    value.getId(), value.getCode(), value.value(locale),
                                    value.getHexColor(), value.getImageUrl()))
                            .toList());
        }
    }

    public record ValueResponse(UUID id, String code, String value, String hexColor, String imageUrl) {
    }
}
