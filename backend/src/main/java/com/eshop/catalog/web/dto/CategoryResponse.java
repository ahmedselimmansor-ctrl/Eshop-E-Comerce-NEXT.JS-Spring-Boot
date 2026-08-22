package com.eshop.catalog.web.dto;

import com.eshop.catalog.domain.Category;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A category, localised to the caller's {@code Accept-Language}.
 *
 * <p>The API resolves the language rather than shipping both {@code nameAr} and {@code nameEn} to
 * every client. Sending both would roughly double the payload of a category tree that is fetched on
 * every page load, and would push the fallback rule ("use Arabic when the English name is blank")
 * into three separate frontends that would each implement it slightly differently.
 */
@Schema(name = "CategoryResponse")
public record CategoryResponse(
        UUID id,
        String slug,
        String name,
        String description,
        String imageUrl,
        String icon,
        int position,
        int productCount,
        UUID parentId,

        @Schema(description = "Materialised path, e.g. /electronics/phones")
        String path,

        short level,
        List<CategoryResponse> children,
        SeoMeta seo
) {

    public record SeoMeta(String title, String description) {
    }

    public static CategoryResponse flat(Category category, String locale) {
        return build(category, locale, List.of());
    }

    /**
     * Rebuilds the nested tree from the flat, level-ordered list the repository returns.
     *
     * <p>One pass, one query: children are attached by parent id from a lookup map, so a
     * five-level taxonomy costs the same as a one-level one.
     */
    public static List<CategoryResponse> tree(List<Category> categories, String locale) {
        java.util.Map<UUID, List<Category>> byParent = new java.util.LinkedHashMap<>();
        List<Category> roots = new ArrayList<>();

        for (Category category : categories) {
            if (category.getParent() == null) {
                roots.add(category);
            } else {
                byParent.computeIfAbsent(category.getParent().getId(), key -> new ArrayList<>())
                        .add(category);
            }
        }
        return roots.stream().map(root -> attach(root, byParent, locale)).toList();
    }

    private static CategoryResponse attach(Category category,
                                           java.util.Map<UUID, List<Category>> byParent,
                                           String locale) {
        List<CategoryResponse> children = byParent.getOrDefault(category.getId(), List.of()).stream()
                .map(child -> attach(child, byParent, locale))
                .toList();
        return build(category, locale, children);
    }

    private static CategoryResponse build(Category category, String locale, List<CategoryResponse> children) {
        boolean english = "en".equalsIgnoreCase(locale);
        return new CategoryResponse(
                category.getId(),
                category.getSlug(),
                category.name(locale),
                english ? category.getDescriptionEn() : category.getDescriptionAr(),
                category.getImageUrl(),
                category.getIcon(),
                category.getPosition(),
                category.getProductCount(),
                category.getParent() == null ? null : category.getParent().getId(),
                category.getPath(),
                category.getLevel(),
                children,
                new SeoMeta(
                        english ? category.getSeoTitleEn() : category.getSeoTitleAr(),
                        english ? category.getSeoDescriptionEn() : category.getSeoDescriptionAr()));
    }
}
