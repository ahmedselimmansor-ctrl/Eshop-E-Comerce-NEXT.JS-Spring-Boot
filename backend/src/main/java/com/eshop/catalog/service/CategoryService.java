package com.eshop.catalog.service;

import com.eshop.catalog.domain.Category;
import com.eshop.catalog.repository.CategoryRepository;
import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.common.util.Slugs;
import com.eshop.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categories;

    /**
     * The full tree. Cached because the navigation menu requests it on every page load while the
     * taxonomy changes perhaps weekly; any write evicts the whole cache below.
     */
    @Cacheable(value = RedisConfig.CACHE_CATEGORY_TREE, key = "'tree:' + #activeOnly")
    @Transactional(readOnly = true)
    public List<Category> tree(boolean activeOnly) {
        return categories.findTree(activeOnly);
    }

    @Cacheable(value = RedisConfig.CACHE_CATEGORIES, key = "'flat:' + #activeOnly")
    @Transactional(readOnly = true)
    public List<Category> flat(boolean activeOnly) {
        return categories.findTree(activeOnly);
    }

    @Transactional(readOnly = true)
    public Category requireBySlug(String slug) {
        return categories.findBySlug(slug)
                .orElseThrow(() -> NotFoundException.of("Category", slug));
    }

    @Transactional(readOnly = true)
    public Category require(UUID id) {
        return categories.findById(id)
                .orElseThrow(() -> NotFoundException.of("Category", id));
    }

    /**
     * Direct children, for the sub-category strip on a category page.
     *
     * <p>Queried rather than read off {@code category.getChildren()}: {@code open-in-view} is off,
     * so a lazy collection touched by the web layer would already be detached.
     */
    @Transactional(readOnly = true)
    public List<Category> childrenOf(UUID categoryId) {
        return categories.findByParentIdOrderByPositionAsc(categoryId);
    }

    /** Breadcrumb ancestors, root first. Derived from the materialised path - no recursion. */
    @Transactional(readOnly = true)
    public List<Category> ancestorsOf(Category category) {
        return categories.findAncestors(category.getPath());
    }

    /**
     * The category and every descendant, so a listing filtered on "Electronics" also returns items
     * filed only under "Electronics / Phones / Smartphones".
     */
    @Transactional(readOnly = true)
    public List<UUID> selfAndDescendantIds(Category category) {
        return categories.findSelfAndDescendantIds(category.getId(), category.getPath());
    }

    /**
     * The category plus every ancestor above it.
     *
     * <p>Needed by the discount engine: a coupon scoped to "Electronics" must match a product filed
     * only under "Electronics / Phones / Smartphones", which means comparing against the whole
     * ancestor chain rather than the leaf alone.
     */
    @Transactional(readOnly = true)
    public Set<UUID> selfAndAncestorIds(UUID categoryId) {
        if (categoryId == null) {
            return Set.of();
        }
        return categories.findById(categoryId)
                .map(category -> {
                    Set<UUID> ids = new LinkedHashSet<>();
                    ids.add(category.getId());
                    categories.findAncestors(category.getPath())
                            .forEach(ancestor -> ids.add(ancestor.getId()));
                    return ids;
                })
                .orElseGet(Set::of);
    }

    // --------------------------------------------------------------- writes --

    @Transactional
    @CacheEvict(value = {RedisConfig.CACHE_CATEGORY_TREE, RedisConfig.CACHE_CATEGORIES}, allEntries = true)
    public Category create(Category category, UUID parentId) {
        if (parentId != null) {
            category.setParent(require(parentId));
        }
        category.setSlug(resolveSlug(category.getSlug(), category.getNameAr(), null));
        Category saved = categories.save(category);
        log.info("category_created id={} slug={}", saved.getId(), saved.getSlug());
        return saved;
    }

    @Transactional
    @CacheEvict(value = {RedisConfig.CACHE_CATEGORY_TREE, RedisConfig.CACHE_CATEGORIES}, allEntries = true)
    public Category update(UUID id, Category changes, UUID parentId) {
        Category category = require(id);

        if (parentId != null && !parentId.equals(currentParentId(category))) {
            Category newParent = require(parentId);
            // The trigger raises on a cycle, but catching it here produces a usable error message
            // instead of a database exception surfacing as a 500.
            if (newParent.getPath().startsWith(category.getPath() + "/")
                    || newParent.getId().equals(category.getId())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "Cannot move a category beneath its own descendant")
                        .withViolation("parentId", "CYCLE",
                                "لا يمكن نقل القسم داخل أحد أقسامه الفرعية", parentId);
            }
            category.setParent(newParent);
        } else if (parentId == null) {
            category.setParent(null);
        }

        category.setNameAr(changes.getNameAr());
        category.setNameEn(changes.getNameEn());
        category.setDescriptionAr(changes.getDescriptionAr());
        category.setDescriptionEn(changes.getDescriptionEn());
        category.setImageUrl(changes.getImageUrl());
        category.setIcon(changes.getIcon());
        category.setPosition(changes.getPosition());
        category.setActive(changes.isActive());
        category.setSeoTitleAr(changes.getSeoTitleAr());
        category.setSeoTitleEn(changes.getSeoTitleEn());
        category.setSeoDescriptionAr(changes.getSeoDescriptionAr());
        category.setSeoDescriptionEn(changes.getSeoDescriptionEn());

        if (changes.getSlug() != null && !changes.getSlug().equals(category.getSlug())) {
            category.setSlug(resolveSlug(changes.getSlug(), changes.getNameAr(), category.getId()));
        }

        return categories.save(category);
    }

    /**
     * Deletion is refused while the category is still in use.
     *
     * <p>Cascading would either orphan products or silently delete them, and re-parenting children
     * automatically hides a structural mistake. Making the caller empty the category first keeps
     * the destructive step explicit.
     */
    @Transactional
    @CacheEvict(value = {RedisConfig.CACHE_CATEGORY_TREE, RedisConfig.CACHE_CATEGORIES}, allEntries = true)
    public void delete(UUID id) {
        Category category = require(id);

        if (categories.countByParentId(id) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Category has child categories")
                    .withViolation("id", "HAS_CHILDREN",
                            "لا يمكن حذف قسم يحتوي على أقسام فرعية", id);
        }
        if (category.getProductCount() > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Category still has products")
                    .withViolation("id", "HAS_PRODUCTS",
                            "لا يمكن حذف قسم يحتوي على منتجات", category.getProductCount());
        }

        categories.delete(category);
        log.info("category_deleted id={}", id);
    }

    @Transactional
    @CacheEvict(value = {RedisConfig.CACHE_CATEGORY_TREE, RedisConfig.CACHE_CATEGORIES}, allEntries = true)
    public void reorder(List<UUID> orderedIds) {
        for (int position = 0; position < orderedIds.size(); position++) {
            Category category = require(orderedIds.get(position));
            category.setPosition(position);
            categories.save(category);
        }
    }

    private UUID currentParentId(Category category) {
        return category.getParent() == null ? null : category.getParent().getId();
    }

    private String resolveSlug(String requested, String fallbackName, UUID selfId) {
        String base = requested != null && !requested.isBlank() ? requested : fallbackName;
        String slug = Slugs.slugifyUnique(base, fallbackName, candidate ->
                Slugs.isReserved(candidate)
                        || categories.findBySlug(candidate)
                                .filter(existing -> !existing.getId().equals(selfId))
                                .isPresent());
        return slug;
    }
}
