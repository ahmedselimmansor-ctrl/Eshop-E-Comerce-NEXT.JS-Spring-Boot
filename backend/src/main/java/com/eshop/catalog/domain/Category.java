package com.eshop.catalog.domain;

import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the product taxonomy.
 *
 * <p>Hierarchy is stored as a materialised path ({@code /electronics/phones/smartphones}) alongside
 * the parent pointer. "Everything under Electronics" is then a single indexed prefix scan rather
 * than a recursive CTE per request - and a category listing runs that query on every page load.
 * The path and {@code level} are maintained by triggers in migration V9, including re-pathing the
 * whole subtree when a node is moved, so application code never has to remember to keep them in
 * sync.
 */
@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
public class Category extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
    @OrderBy("position ASC, nameAr ASC")
    private List<Category> children = new ArrayList<>();

    @Column(name = "slug", nullable = false, unique = true, length = 140)
    private String slug;

    @Column(name = "name_ar", nullable = false, length = 160)
    private String nameAr;

    @Column(name = "name_en", length = 160)
    private String nameEn;

    @Column(name = "description_ar")
    private String descriptionAr;

    @Column(name = "description_en")
    private String descriptionEn;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "icon", length = 60)
    private String icon;

    @Column(name = "position", nullable = false)
    private int position = 0;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Maintained by the {@code trg_categories_path} trigger; never assign this directly. */
    @Column(name = "path", nullable = false, insertable = false, updatable = false)
    private String path;

    @Column(name = "level", nullable = false, insertable = false, updatable = false)
    private short level;

    /** Denormalised count of active products, refreshed by trigger. */
    @Column(name = "product_count", nullable = false)
    private int productCount = 0;

    @Column(name = "seo_title_ar", length = 200)
    private String seoTitleAr;

    @Column(name = "seo_title_en", length = 200)
    private String seoTitleEn;

    @Column(name = "seo_description_ar", length = 400)
    private String seoDescriptionAr;

    @Column(name = "seo_description_en", length = 400)
    private String seoDescriptionEn;

    public String name(String locale) {
        return "en".equalsIgnoreCase(locale) && nameEn != null && !nameEn.isBlank() ? nameEn : nameAr;
    }

    public boolean isRoot() {
        return parent == null;
    }

    /** Prefix that matches this category and every descendant, for a {@code LIKE} lookup. */
    public String descendantPathPrefix() {
        return path + "/%";
    }
}
