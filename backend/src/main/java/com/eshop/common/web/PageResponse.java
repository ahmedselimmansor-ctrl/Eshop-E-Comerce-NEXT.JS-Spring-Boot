package com.eshop.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The pagination envelope every list endpoint returns.
 *
 * <p>Spring Data's {@code PageImpl} is deliberately not serialised directly: its JSON shape is an
 * implementation detail that has changed between Spring versions and would drag {@code Pageable},
 * {@code Sort}, and {@code unpaged} internals into the public contract.
 */
@Schema(name = "PageResponse")
public record PageResponse<T>(
        List<T> content,
        PageMeta page
) {

    public record PageMeta(
            @Schema(description = "Zero-based page index") int number,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last
    ) {
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), meta(page));
    }

    /** Maps entities to DTOs while preserving the page metadata. */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), meta(page));
    }

    /** For hand-built pages, e.g. results assembled from an Elasticsearch response. */
    public static <T> PageResponse<T> of(List<T> content, int number, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(content, new PageMeta(
                number, size, totalElements, totalPages, number == 0, number >= totalPages - 1));
    }

    public static <T> PageResponse<T> empty(int size) {
        return new PageResponse<>(List.of(), new PageMeta(0, size, 0, 0, true, true));
    }

    private static PageMeta meta(Page<?> page) {
        return new PageMeta(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
