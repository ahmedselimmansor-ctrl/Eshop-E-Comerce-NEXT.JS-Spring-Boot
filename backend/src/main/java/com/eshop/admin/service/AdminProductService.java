package com.eshop.admin.service;

import com.eshop.catalog.domain.Attribute;
import com.eshop.catalog.domain.AttributeValue;
import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductAttributeValue;
import com.eshop.catalog.domain.ProductImage;
import com.eshop.catalog.domain.ProductStatus;
import com.eshop.catalog.domain.ProductType;
import com.eshop.catalog.domain.ProductVariant;
import com.eshop.catalog.domain.VariantAttributeValue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.eshop.catalog.repository.AttributeRepository;
import com.eshop.catalog.repository.BrandRepository;
import com.eshop.catalog.repository.CategoryRepository;
import com.eshop.catalog.repository.ProductRepository;
import com.eshop.catalog.repository.ProductVariantRepository;
import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.common.outbox.OutboxEvent;
import com.eshop.common.outbox.OutboxService;
import com.eshop.common.util.HtmlSanitizer;
import com.eshop.common.util.Slugs;
import com.eshop.inventory.domain.InventoryItem;
import com.eshop.inventory.repository.InventoryItemRepository;
import com.eshop.inventory.repository.WarehouseRepository;
import com.eshop.admin.web.dto.AdminProductRow;
import com.eshop.inventory.service.InventoryService;
import com.eshop.admin.web.dto.AdminProductDetail;
import com.eshop.admin.web.dto.ProductWriteRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Catalog writes.
 *
 * <p>Two things happen on every write and are easy to forget individually, so they are centralised
 * here: rich text is sanitised before it is stored, and a {@code product.updated} event is queued so
 * the search index catches up. Indexing is never called inline - that would tie a catalog edit to
 * the availability of the search cluster and would index changes that later roll back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminProductService {

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final AttributeRepository attributes;
    private final InventoryItemRepository inventoryItems;
    private final WarehouseRepository warehouses;
    private final OutboxService outbox;
    private final InventoryService inventory;

    /**
     * Admin product search, mapped to rows inside the transaction.
     *
     * <p>The mapping deliberately happens here rather than in the controller. {@code open-in-view} is
     * off, so a {@code Page<Product>} handed back to a controller is a page of entities whose lazy
     * associations are already unreachable - reading {@code variants} or {@code images} from the
     * response mapper throws {@code LazyInitializationException} at request time while compiling
     * perfectly well. Returning finished rows makes that mistake impossible to write.
     *
     * <p>{@code images} and {@code variants} are initialised rather than join-fetched: a fetched
     * collection would force Hibernate to page in memory instead of in SQL. {@code @BatchSize} turns
     * each into one extra query for the whole page.
     */
    @Transactional(readOnly = true)
    public Page<AdminProductRow> search(String query, ProductStatus status, Pageable pageable,
                                        String locale) {
        Page<Product> page = products.adminSearch(query, status, pageable);
        page.forEach(product -> {
            product.getImages().size();
            product.getVariants().size();
        });

        Map<UUID, Integer> availability = inventory.availabilityByProduct(
                page.getContent().stream().map(Product::getId).toList());

        return page.map(product -> AdminProductRow.from(
                product, locale, availability.get(product.getId())));
    }

    /**
     * One product, fully loaded for the editor.
     *
     * <p>Everything the DTO touches is initialised inside this transaction - {@code open-in-view} is
     * off, and the DTO reads four separate collections.
     */
    @Transactional(readOnly = true)
    public AdminProductDetail detail(UUID productId) {
        Product product = products.findDetailById(productId)
                .orElseThrow(() -> NotFoundException.of("Product", productId));

        product.getImages().size();
        product.getCategories().size();
        product.getAttributeValues().forEach(link -> {
            if (link.getAttributeValue() != null) {
                link.getAttributeValue().getId();
            }
        });
        product.getVariants().forEach(variant -> variant.getAttributeValues()
                .forEach(link -> link.getAttributeValue().getId()));

        return AdminProductDetail.from(product);
    }

    @Transactional
    public Product create(ProductWriteRequest request, UUID actorId) {
        assertSkuAvailable(request.sku(), null);

        Product product = new Product();
        product.setSku(request.sku().trim());
        apply(product, request);
        product.setSlug(resolveSlug(request.slug(), request.nameEn(), request.nameAr(), null));

        if (request.status() == ProductStatus.ACTIVE) {
            product.publish();
        }

        products.saveAndFlush(product);
        syncImages(product, request);
        syncAttributes(product, request);
        ensureInventoryRow(product, null);

        outbox.record("Product", product.getId(), OutboxEvent.PRODUCT_UPDATED,
                "productId", product.getId().toString(), "sku", product.getSku());

        log.info("product_created id={} sku={} actor={}", product.getId(), product.getSku(), actorId);
        return product;
    }

    @Transactional
    public Product update(UUID productId, ProductWriteRequest request, UUID actorId) {
        Product product = products.findDetailById(productId)
                .orElseThrow(() -> NotFoundException.of("Product", productId));

        if (!product.getSku().equals(request.sku())) {
            assertSkuAvailable(request.sku(), productId);
            product.setSku(request.sku().trim());
        }

        ProductStatus previousStatus = product.getStatus();
        apply(product, request);

        if (request.slug() != null && !request.slug().equals(product.getSlug())) {
            product.setSlug(resolveSlug(request.slug(), request.nameEn(), request.nameAr(), productId));
        }
        if (previousStatus != ProductStatus.ACTIVE && request.status() == ProductStatus.ACTIVE) {
            product.publish();
        }

        syncImages(product, request);
        syncAttributes(product, request);
        products.save(product);

        outbox.record("Product", product.getId(), OutboxEvent.PRODUCT_UPDATED,
                "productId", product.getId().toString(), "sku", product.getSku());

        log.info("product_updated id={} actor={}", productId, actorId);
        return product;
    }

    /**
     * Soft delete.
     *
     * <p>Order items reference products for "buy it again" and sales reporting; a hard delete would
     * either orphan that history or cascade it away. The slug stays claimed too, so an old link
     * cannot silently start pointing at a different product.
     */
    @Transactional
    public void delete(UUID productId, UUID actorId) {
        Product product = products.findById(productId)
                .orElseThrow(() -> NotFoundException.of("Product", productId));

        product.setDeletedAt(Instant.now());
        product.setStatus(ProductStatus.ARCHIVED);
        products.save(product);

        outbox.record("Product", productId, OutboxEvent.PRODUCT_DELETED,
                "productId", productId.toString());

        log.info("product_deleted id={} actor={}", productId, actorId);
    }

    /** Bulk status changes from the product list - publish, archive, or feature a selection. */
    @Transactional
    public int bulkUpdateStatus(List<UUID> productIds, ProductStatus status, UUID actorId) {
        int updated = 0;

        for (UUID productId : productIds) {
            Product product = products.findById(productId).orElse(null);
            if (product == null || product.isDeleted()) {
                continue;
            }
            if (status == ProductStatus.ACTIVE) {
                product.publish();
            } else {
                product.setStatus(status);
            }
            products.save(product);
            outbox.record("Product", productId, OutboxEvent.PRODUCT_UPDATED,
                    "productId", productId.toString());
            updated++;
        }

        log.info("products_bulk_status_updated count={} status={} actor={}", updated, status, actorId);
        return updated;
    }

    // -------------------------------------------------------------- variants --

    @Transactional
    public ProductVariant addVariant(UUID productId, ProductWriteRequest.VariantSpec spec, UUID actorId) {
        Product product = products.findDetailById(productId)
                .orElseThrow(() -> NotFoundException.of("Product", productId));

        if (variants.existsBySku(spec.sku())) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "Variant SKU already exists")
                    .withViolation("sku", "DUPLICATE", "كود المنتج مستخدم بالفعل", spec.sku());
        }

        ProductVariant variant = new ProductVariant();
        variant.setSku(spec.sku().trim());
        variant.setBarcode(spec.barcode());
        variant.setPrice(spec.price());
        variant.setCompareAtPrice(spec.compareAtPrice());
        variant.setCostPrice(spec.costPrice());
        variant.setWeightGrams(spec.weightGrams());
        variant.setImageUrl(spec.imageUrl());
        variant.setPosition(spec.position());
        variant.setActive(spec.active());
        product.addVariant(variant);

        // A product with variants is VARIABLE by definition - inferred rather than asked for, so
        // the two can never disagree.
        product.setType(ProductType.VARIABLE);

        variants.saveAndFlush(variant);
        applyVariantAxes(variant, spec.attributeValueIds());
        ensureInventoryRow(product, variant);
        products.save(product);

        outbox.record("Product", productId, OutboxEvent.PRODUCT_UPDATED,
                "productId", productId.toString());

        log.info("variant_created product={} sku={} actor={}", productId, variant.getSku(), actorId);
        return variant;
    }

    @Transactional
    public void deleteVariant(UUID variantId, UUID actorId) {
        ProductVariant variant = variants.findById(variantId)
                .orElseThrow(() -> NotFoundException.of("ProductVariant", variantId));

        UUID productId = variant.getProduct().getId();

        // Deactivated, not deleted: order items reference it, and the SKU should stay reserved.
        variant.setActive(false);
        variants.save(variant);

        outbox.record("Product", productId, OutboxEvent.PRODUCT_UPDATED,
                "productId", productId.toString());

        log.info("variant_deactivated id={} actor={}", variantId, actorId);
    }

    // --------------------------------------------------------------- helpers --

    private void apply(Product product, ProductWriteRequest request) {
        product.setNameAr(request.nameAr().trim());
        product.setNameEn(request.nameEn() == null ? null : request.nameEn().trim());
        product.setShortDescriptionAr(request.shortDescriptionAr());
        product.setShortDescriptionEn(request.shortDescriptionEn());
        // Sanitised on the way in, so the stored value is safe for every consumer - web, mobile,
        // email, and export - rather than relying on each of them to escape correctly.
        product.setDescriptionAr(HtmlSanitizer.richText(request.descriptionAr()));
        product.setDescriptionEn(HtmlSanitizer.richText(request.descriptionEn()));
        product.setBasePrice(request.basePrice());
        product.setCompareAtPrice(request.compareAtPrice());
        product.setCostPrice(request.costPrice());
        product.setCurrency(request.currency() == null ? "EGP" : request.currency());
        product.setTaxRate(request.taxRate() == null ? product.getTaxRate() : request.taxRate());
        product.setWeightGrams(request.weightGrams());
        product.setLengthMm(request.lengthMm());
        product.setWidthMm(request.widthMm());
        product.setHeightMm(request.heightMm());
        product.setFeatured(request.featured());
        product.setDigital(request.digital());
        product.setRequiresShipping(!request.digital());
        product.setSeoTitleAr(request.seoTitleAr());
        product.setSeoTitleEn(request.seoTitleEn());
        product.setSeoDescriptionAr(request.seoDescriptionAr());
        product.setSeoDescriptionEn(request.seoDescriptionEn());

        if (request.status() != ProductStatus.ACTIVE) {
            product.setStatus(request.status());
        }

        product.setBrand(request.brandId() == null ? null
                : brands.findById(request.brandId())
                        .orElseThrow(() -> NotFoundException.of("Brand", request.brandId())));

        product.setPrimaryCategory(request.primaryCategoryId() == null ? null
                : categories.findById(request.primaryCategoryId())
                        .orElseThrow(() -> NotFoundException.of("Category", request.primaryCategoryId())));

        if (request.categoryIds() != null) {
            product.getCategories().clear();
            request.categoryIds().forEach(categoryId ->
                    categories.findById(categoryId).ifPresent(product.getCategories()::add));
        }
    }

    private void syncImages(Product product, ProductWriteRequest request) {
        if (request.images() == null) {
            return;
        }

        product.getImages().clear();
        int position = 0;

        for (ProductWriteRequest.ImageSpec spec : request.images()) {
            ProductImage image = new ProductImage();
            image.setUrl(spec.url());
            image.setObjectKey(spec.objectKey());
            image.setAltAr(spec.altAr());
            image.setAltEn(spec.altEn());
            image.setWidth(spec.width());
            image.setHeight(spec.height());
            image.setBlurhash(spec.blurhash());
            image.setPosition(position);
            // Exactly one primary, enforced by a partial unique index; the first image wins unless
            // one is explicitly flagged.
            image.setPrimary(spec.primary() || position == 0);
            product.addImage(image);
            position++;
        }

        long primaries = product.getImages().stream().filter(ProductImage::isPrimary).count();
        if (primaries > 1) {
            product.getImages().forEach(image -> image.setPrimary(false));
            // Insertion-ordered (LinkedHashSet, ordered by position), so "first" still means the
            // lowest-positioned image.
            product.getImages().stream().findFirst().ifPresent(image -> image.setPrimary(true));
        }
    }

    private void syncAttributes(Product product, ProductWriteRequest request) {
        if (request.attributeValueIds() == null) {
            return;
        }

        product.getAttributeValues().clear();

        for (UUID valueId : request.attributeValueIds()) {
            AttributeValue value = findAttributeValue(valueId);

            ProductAttributeValue link = new ProductAttributeValue();
            link.setProduct(product);
            link.setAttribute(value.getAttribute());
            link.setAttributeValue(value);
            product.getAttributeValues().add(link);
        }
    }

    private void applyVariantAxes(ProductVariant variant, List<UUID> attributeValueIds) {
        if (attributeValueIds == null || attributeValueIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A variant must specify at least one attribute value")
                    .withViolation("attributeValueIds", "REQUIRED",
                            "يجب تحديد خيارات المنتج", null);
        }

        variant.getAttributeValues().clear();

        for (UUID valueId : attributeValueIds) {
            AttributeValue value = findAttributeValue(valueId);
            Attribute attribute = value.getAttribute();

            if (!attribute.isVariantAxis()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Attribute " + attribute.getCode() + " is not a variant axis")
                        .withViolation("attributeValueIds", "NOT_VARIANT_AXIS",
                                "هذه الخاصية ليست من خيارات المنتج", attribute.getCode());
            }

            variant.getAttributeValues().add(new VariantAttributeValue(variant, value));
        }
    }

    private AttributeValue findAttributeValue(UUID valueId) {
        return attributes.findAllWithValues().stream()
                .flatMap(attribute -> attribute.getValues().stream())
                .filter(value -> value.getId().equals(valueId))
                .findFirst()
                .orElseThrow(() -> NotFoundException.of("AttributeValue", valueId));
    }

    /**
     * Creates the stock row for a new SKU at zero.
     *
     * <p>Without a row, availability queries return nothing and the product is simply invisible in
     * stock-filtered listings - a confusing failure that looks like a search bug.
     */
    private void ensureInventoryRow(Product product, ProductVariant variant) {
        var warehouse = warehouses.findByActiveTrueOrderByPriorityAscCodeAsc().stream().findFirst();
        if (warehouse.isEmpty()) {
            log.warn("no_active_warehouse - inventory row not created for product {}", product.getId());
            return;
        }

        boolean exists = inventoryItems
                .findForSku(product.getId(), variant == null ? null : variant.getId())
                .stream()
                .anyMatch(item -> item.getWarehouse().getId().equals(warehouse.get().getId()));

        if (exists) {
            return;
        }

        InventoryItem item = new InventoryItem();
        item.setProduct(product);
        item.setVariant(variant);
        item.setWarehouse(warehouse.get());
        item.setQuantityOnHand(0);
        inventoryItems.save(item);
    }

    private void assertSkuAvailable(String sku, UUID selfId) {
        products.findBySkuAndDeletedAtIsNull(sku)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "SKU already exists")
                            .withViolation("sku", "DUPLICATE", "كود المنتج مستخدم بالفعل", sku);
                });
    }

    /** Product slugs prefer the English name for a cleaner URL, falling back to Arabic. */
    private String resolveSlug(String requested, String nameEn, String nameAr, UUID selfId) {
        String base = requested != null && !requested.isBlank()
                ? requested
                : (nameEn != null && !nameEn.isBlank() ? nameEn : nameAr);

        return Slugs.slugifyUnique(base, nameAr, candidate ->
                Slugs.isReserved(candidate)
                        || products.findBySlugAndDeletedAtIsNull(candidate)
                                .filter(existing -> !existing.getId().equals(selfId))
                                .isPresent());
    }

    /** Distinct attribute codes in use, for the admin filter UI. */
    @Transactional(readOnly = true)
    public Map<String, List<String>> attributeCatalogue() {
        return attributes.findAllWithValues().stream().collect(Collectors.toMap(
                Attribute::getCode,
                attribute -> attribute.getValues().stream().map(AttributeValue::getCode).toList(),
                (first, second) -> first,
                java.util.LinkedHashMap::new));
    }
}
