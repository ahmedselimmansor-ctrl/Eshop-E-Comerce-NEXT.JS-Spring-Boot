package com.eshop.support;

import com.eshop.catalog.domain.Brand;
import com.eshop.catalog.domain.Category;
import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductStatus;
import com.eshop.catalog.repository.BrandRepository;
import com.eshop.catalog.repository.CategoryRepository;
import com.eshop.catalog.repository.ProductRepository;
import com.eshop.inventory.domain.InventoryItem;
import com.eshop.inventory.domain.Warehouse;
import com.eshop.inventory.repository.InventoryItemRepository;
import com.eshop.order.domain.ShippingMethod;
import com.eshop.order.repository.ShippingMethodRepository;
import com.eshop.review.domain.Review;
import com.eshop.review.repository.ReviewRepository;
import com.eshop.inventory.repository.WarehouseRepository;
import com.eshop.user.domain.Address;
import com.eshop.user.domain.Role;
import com.eshop.user.domain.User;
import com.eshop.user.domain.UserStatus;
import com.eshop.user.repository.AddressRepository;
import com.eshop.user.repository.RoleRepository;
import com.eshop.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Builds the small amount of data a test needs, and clears it again afterwards.
 *
 * <p>{@link #reset()} truncates the transactional tables but deliberately leaves the reference data
 * the migrations seed - roles, permissions, shipping zones. Those are part of the schema's contract,
 * not test fixtures, and a test that had to recreate them would be asserting against its own
 * fixture rather than against what the application actually ships.
 */
@Component
@RequiredArgsConstructor
public class TestFixtures {

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final RoleRepository roles;
    private final BrandRepository brands;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final WarehouseRepository warehouses;
    private final InventoryItemRepository inventoryItems;
    private final ReviewRepository reviews;
    private final AddressRepository addresses;
    private final ShippingMethodRepository shippingMethods;
    private final PasswordEncoder passwords;

    /**
     * Tables the migrations own and tests must not empty.
     *
     * <p>Roles, permissions, settings, shipping zones and the attribute vocabulary are seeded by
     * {@code R__seed_reference_data.sql}. They are part of what the application ships, not fixture
     * data - a test that recreated them would be asserting against its own copy rather than against
     * the real thing.
     */
    private static final Set<String> PRESERVED = Set.of(
            "flyway_schema_history",
            "roles", "permissions", "role_permissions",
            "settings",
            "shipping_zones", "shipping_methods",
            "attributes", "attribute_values");

    /**
     * Empties every other table.
     *
     * <p>The list is read from the schema rather than hard-coded, so a new migration cannot leave a
     * table quietly un-truncated and let state bleed from one test into the next. One
     * {@code TRUNCATE ... CASCADE} handles the foreign keys, so ordering does not matter.
     */
    @Transactional
    public void reset() {
        List<String> tables = jdbc.queryForList("""
                SELECT tablename FROM pg_tables WHERE schemaname = 'public'
                """, String.class).stream()
                .filter(table -> !PRESERVED.contains(table))
                .toList();

        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
    }

    // ----------------------------------------------------------------- users --

    public User customer(String email) {
        return user(email, Role.CUSTOMER);
    }

    public User superAdmin(String email) {
        return user(email, Role.SUPER_ADMIN);
    }

    @Transactional
    public User user(String email, String roleName) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwords.encode(PASSWORD));
        user.setFirstName("Test");
        user.setLastName("User");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        user.getRoles().add(roles.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException(
                        "Role " + roleName + " is missing - the reference-data migration did not run")));
        return users.save(user);
    }

    public static final String PASSWORD = "Passw0rd!x";

    // --------------------------------------------------------------- catalog --

    @Transactional
    public Product product(String sku, String nameAr, String nameEn, BigDecimal price,
                           ProductStatus status) {

        Product product = new Product();
        product.setSku(sku);
        product.setSlug(sku.toLowerCase());
        product.setNameAr(nameAr);
        product.setNameEn(nameEn);
        product.setBasePrice(price);
        product.setStatus(status);
        product.setBrand(brand());
        product.setPrimaryCategory(category());
        if (status == ProductStatus.ACTIVE) {
            product.setPublishedAt(Instant.now());
        }
        return products.save(product);
    }

    /** An active product at 100.00 with stock, which is what most tests want. */
    public Product sellableProduct(String sku, int stock) {
        Product product = product(sku, "منتج", "Product", new BigDecimal("100.00"),
                ProductStatus.ACTIVE);
        stock(product, stock);
        return product;
    }

    @Transactional
    public InventoryItem stock(Product product, int quantity) {
        InventoryItem item = new InventoryItem();
        item.setProduct(product);
        item.setWarehouse(warehouse());
        item.setQuantityOnHand(quantity);
        return inventoryItems.save(item);
    }


    /** The single stock row for a product, for tests that need to act on it. */
    @Transactional
    public InventoryItem stockOf(Product product) {
        return inventoryItems.findByProductIds(List.of(product.getId())).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No stock row for " + product.getSku()));
    }

    /**
     * Holds stock the way an unpaid order does, so a test can tell on-hand from available.
     */
    @Transactional
    public InventoryItem reserve(InventoryItem item, int quantity) {
        InventoryItem managed = inventoryItems.findById(item.getId()).orElseThrow();
        managed.setQuantityReserved(quantity);
        return inventoryItems.save(managed);
    }

    // --------------------------------------------------------------- reviews --

    /** A pending review, which is the only state the moderation queue cares about. */
    @Transactional
    public Review review(Product product, User author, int rating) {
        Review review = new Review();
        review.setProduct(product);
        review.setUser(author);
        review.setRating((short) rating);
        review.setTitle("Title");
        review.setBody("Body of the review.");
        review.setStatus(Review.Status.PENDING);
        review.setVerifiedPurchase(true);
        return reviews.save(review);
    }

    // ------------------------------------------------------------- addresses --

    /**
     * A Cairo address.
     *
     * <p>The governorate is written in Arabic because that is how the shipping zones store it - the
     * seeded "Greater Cairo" zone matches on {@code القاهرة}, and an English spelling silently
     * matches no zone at all, leaving checkout with no shipping method to offer.
     */
    @Transactional
    public Address address(User user) {
        Address address = new Address();
        address.setUser(user);
        address.setRecipientName("Sara Ahmed");
        address.setPhone("+201001234567");
        address.setGovernorate("القاهرة");
        address.setCity("مدينة نصر");
        address.setStreet("12 شارع عباس العقاد");
        address.setBuilding("5");
        return addresses.save(address);
    }

    /** The cheapest shipping method serving Cairo. */
    @Transactional
    public ShippingMethod shippingMethod() {
        return shippingMethods.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No shipping methods - the reference-data migration did not run"));
    }

    // ------------------------------------------------------------ taxonomy --

    /**
     * A category and, optionally, its parent.
     *
     * <p>Goes through the repository inside a transaction like every other fixture. Raw
     * {@code JdbcTemplate} writes issued straight from a test method are not visible to the
     * application's own connections, so a tree built that way looks empty to the endpoint under test.
     */
    @Transactional
    public Category category(String slug, String nameAr, String nameEn, Category parent) {
        Category category = new Category();
        category.setSlug(slug);
        category.setNameAr(nameAr);
        category.setNameEn(nameEn);
        category.setParent(parent);
        category.setActive(true);
        return categories.saveAndFlush(category);
    }

    @Transactional
    public Brand brand() {
        return brands.findBySlug("test-brand").orElseGet(() -> {
            Brand brand = new Brand();
            brand.setSlug("test-brand");
            brand.setNameAr("علامة");
            brand.setNameEn("Test Brand");
            return brands.save(brand);
        });
    }

    @Transactional
    public Category category() {
        return categories.findBySlug("test-category").orElseGet(() -> {
            Category category = new Category();
            category.setSlug("test-category");
            category.setNameAr("فئة");
            category.setNameEn("Test Category");
            return categories.save(category);
        });
    }

    @Transactional
    public Warehouse warehouse() {
        return warehouses.findByCode("TST").orElseGet(() -> {
            Warehouse warehouse = new Warehouse();
            warehouse.setCode("TST");
            warehouse.setNameAr("مخزن");
            warehouse.setNameEn("Test Warehouse");
            warehouse.setGovernorate("القاهرة");
            warehouse.setCity("القاهرة");
            return warehouses.save(warehouse);
        });
    }
}
