package com.eshop.order.service;

import com.eshop.cart.domain.Cart;
import com.eshop.cart.domain.CartItem;
import com.eshop.cart.repository.CartRepository;
import com.eshop.cart.service.CartService;
import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductVariant;
import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.common.money.Money;
import com.eshop.common.outbox.OutboxEvent;
import com.eshop.common.outbox.OutboxService;
import com.eshop.config.AppProperties;
import com.eshop.inventory.service.InventoryService;
import com.eshop.order.domain.Order;
import com.eshop.order.domain.OrderItem;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.domain.OrderStatusHistory;
import com.eshop.order.repository.OrderRepository;
import com.eshop.order.web.dto.CheckoutRequest;
import com.eshop.payment.domain.PaymentMethod;
import com.eshop.payment.domain.PaymentProviderType;
import com.eshop.promotion.service.CouponService;
import com.eshop.settings.service.SettingsService;
import com.eshop.user.domain.Address;
import com.eshop.user.domain.User;
import com.eshop.user.repository.AddressRepository;
import com.eshop.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a cart into an order, atomically.
 *
 * <p>Everything in {@link #create} runs in one transaction, and the order in which it happens is
 * the whole design:
 *
 * <ol>
 *   <li><b>Re-price every line from the catalog.</b> The cart's stored prices are a display cache;
 *       the amount charged is read fresh. A price that moved since the item was added surfaces as
 *       {@code PRICE_CHANGED} rather than being silently honoured or silently raised.</li>
 *   <li><b>Revalidate the coupon under a row lock.</b> The cart screen's check was advisory.</li>
 *   <li><b>Reserve stock with {@code SELECT … FOR UPDATE}.</b> This is the point where concurrent
 *       checkouts for the last unit are serialised.</li>
 *   <li><b>Write the order, the redemption, and the outbox event.</b> All in the same commit, so
 *       there is no state where an order exists without its stock hold, or a confirmation event
 *       fires for an order that rolled back.</li>
 * </ol>
 *
 * <p>The payment provider is deliberately <em>not</em> called here — see
 * {@link CheckoutService#placeOrder}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreationService {

    private final CartRepository carts;
    private final CartService cartService;
    private final OrderRepository orders;
    private final AddressRepository addresses;
    private final UserRepository users;
    private final InventoryService inventory;
    private final CouponService coupons;
    private final ShippingService shipping;
    private final SettingsService settings;
    private final OutboxService outbox;
    private final AppProperties properties;

    /**
     * @param userId    {@code null} for a guest checkout
     * @param sessionId guest cart identifier, ignored when {@code userId} is present
     */
    @Transactional
    public Order create(UUID userId, String sessionId, CheckoutRequest request,
                        String clientIp, String userAgent, String locale) {

        Cart cart = cartService.find(userId, sessionId)
                .filter(candidate -> candidate.isOwnedBy(userId, sessionId))
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_EMPTY, "No active cart"));

        if (cart.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY, "Cart has no items");
        }
        if (userId == null && !settings.bool(SettingsService.CHECKOUT_GUEST_ENABLED, true)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Guest checkout is disabled");
        }
        if (userId == null && (request.guestEmail() == null || request.guestPhone() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Guest checkout requires email and phone")
                    .withViolation("guestEmail", "REQUIRED", "البريد الإلكتروني مطلوب", null)
                    .withViolation("guestPhone", "REQUIRED", "رقم الهاتف مطلوب", null);
        }

        String currency = cart.getCurrency();
        Map<String, Object> shippingSnapshot = resolveShippingAddress(userId, request);
        String governorate = String.valueOf(shippingSnapshot.get("governorate"));

        // ---- 1. Re-price from the catalog -------------------------------------
        List<RepricedLine> lines = reprice(cart, currency);
        Money subtotal = lines.stream()
                .map(RepricedLine::lineSubtotal)
                .reduce(Money.zero(currency), Money::plus);

        BigDecimal minimumOrder = settings.decimal(SettingsService.CHECKOUT_MIN_ORDER, BigDecimal.ZERO);
        if (subtotal.amount().compareTo(minimumOrder) < 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order below the store minimum")
                    .withViolation("subtotal", "BELOW_MINIMUM",
                            "الحد الأدنى للطلب " + minimumOrder.toPlainString() + " جنيه", subtotal.amount());
        }

        // ---- 2. Coupon, under lock --------------------------------------------
        CouponService.Evaluation coupon = CouponService.Evaluation.none();
        if (request.couponCode() != null && !request.couponCode().isBlank()) {
            coupon = coupons.validateForCheckout(request.couponCode(),
                    cartService.priceableLines(cart), userId);
        }
        Money discount = coupon.discount();

        // ---- 3. Shipping -------------------------------------------------------
        int totalGrams = lines.stream().mapToInt(RepricedLine::shippingWeightGrams).sum();
        ShippingService.PricedMethod method =
                shipping.priceFor(request.shippingMethodId(), governorate, subtotal, totalGrams);

        Money shippingCost = coupon.freeShipping() ? Money.zero(currency) : method.cost();

        // ---- 4. Cash on delivery ----------------------------------------------
        Money codFee = Money.zero(currency);
        if (request.paymentProvider() == PaymentProviderType.COD) {
            if (!method.method().isSupportsCod()) {
                throw new BusinessException(ErrorCode.COD_NOT_SUPPORTED,
                        "Shipping method " + method.method().getCode() + " does not carry cash");
            }
            codFee = Money.of(settings.decimal(SettingsService.CHECKOUT_COD_FEE,
                    properties.getCheckout().getCodFee()), currency);

            Money exposure = subtotal.minus(discount).plus(shippingCost).plus(codFee);
            BigDecimal codLimit = properties.getPayment().getCod().getMaxOrderAmount();
            if (exposure.amount().compareTo(codLimit) > 0) {
                throw new BusinessException(ErrorCode.COD_NOT_SUPPORTED,
                        "Order exceeds the cash-on-delivery limit")
                        .withViolation("paymentProvider", "COD_LIMIT",
                                "الدفع عند الاستلام متاح للطلبات حتى " + codLimit.toPlainString() + " جنيه",
                                exposure.amount());
            }
        }

        // ---- 5. Tax -------------------------------------------------------------
        BigDecimal taxRate = settings.decimal(SettingsService.CHECKOUT_TAX_RATE,
                properties.getCheckout().getDefaultTaxRate());
        Money taxable = subtotal.minus(discount).atLeastZero();
        Money tax = taxable.percentage(taxRate);
        Money grandTotal = taxable.plus(tax).plus(shippingCost).plus(codFee);

        // ---- 6. Persist the order ----------------------------------------------
        Order order = new Order();
        order.setOrderNumber(orders.nextOrderNumber());
        order.setUser(userId == null ? null : users.findActiveById(userId).orElse(null));
        order.setGuestEmail(userId == null ? request.guestEmail() : null);
        order.setGuestPhone(userId == null ? request.guestPhone() : null);
        order.setCurrency(currency);
        order.setShippingAddress(shippingSnapshot);
        order.setBillingAddress(resolveBillingAddress(userId, request, shippingSnapshot));
        order.setShippingMethodId(method.method().getId());
        order.setShippingMethodNameAr(method.method().getNameAr());
        order.setShippingMethodNameEn(method.method().getNameEn());
        order.setCustomerNote(request.customerNote());
        order.setLocale(locale);
        order.setIp(clientIp);
        order.setUserAgent(userAgent);
        order.setCouponCode(coupon.valid() ? coupon.code() : null);
        order.setSubtotal(subtotal.amount());
        order.setDiscountTotal(discount.amount());
        order.setTaxTotal(tax.amount());
        order.setShippingTotal(shippingCost.amount());
        order.setCodFee(codFee.amount());
        order.setGrandTotal(grandTotal.amount());
        order.setStatus(OrderStatus.PENDING_PAYMENT);

        apportion(lines, discount, tax, currency);
        lines.forEach(line -> order.addItem(line.toOrderItem()));

        OrderStatusHistory placed = new OrderStatusHistory();
        placed.setOrder(order);
        placed.setToStatus(OrderStatus.PENDING_PAYMENT);
        placed.setNote("Order placed");
        placed.setChangedBy(userId);
        placed.setChangedByType(userId == null
                ? OrderStatusHistory.ActorType.SYSTEM : OrderStatusHistory.ActorType.CUSTOMER);
        order.getHistory().add(placed);

        orders.saveAndFlush(order);

        // ---- 7. Hold the stock ---------------------------------------------------
        inventory.reserve(order.getId(), lines.stream()
                .map(line -> new InventoryService.Line(
                        line.product().getId(),
                        line.variant() == null ? null : line.variant().getId(),
                        line.quantity()))
                .toList());

        // ---- 8. Redeem the coupon -------------------------------------------------
        if (coupon.valid()) {
            coupons.redeem(coupon.couponId(), userId, order.getId(), discount);
        }

        // ---- 9. Retire the cart ---------------------------------------------------
        cart.markConverted();
        carts.save(cart);

        // ---- 10. Announce it -------------------------------------------------------
        outbox.record("Order", order.getId(), OutboxEvent.ORDER_PLACED,
                "orderId", order.getId().toString(),
                "orderNumber", order.getOrderNumber(),
                "userId", userId == null ? null : userId.toString(),
                "email", order.contactEmail(),
                "grandTotal", grandTotal.amount().toPlainString(),
                "currency", currency,
                "itemCount", order.totalQuantity(),
                "paymentProvider", request.paymentProvider().name());

        log.info("order_placed number={} user={} total={} provider={} items={}",
                order.getOrderNumber(), userId, grandTotal, request.paymentProvider(), lines.size());

        return order;
    }

    // ------------------------------------------------------------- repricing --

    /**
     * Reads current catalog prices and rejects the checkout if any line has moved.
     *
     * <p>Silently charging a new price would be indefensible; silently honouring a stale one is a
     * pricing leak that a scripted client could exploit. Both are avoided by making the customer
     * confirm.
     */
    private List<RepricedLine> reprice(Cart cart, String currency) {
        List<RepricedLine> lines = new ArrayList<>(cart.getItems().size());
        BusinessException priceChanged = null;

        for (CartItem item : cart.getItems()) {
            Product product = item.getProduct();
            ProductVariant variant = item.getVariant();

            if (!product.isPurchasable()) {
                throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE,
                        "Product " + product.getSku() + " is no longer available")
                        .withProperty("productId", product.getId().toString());
            }
            if (variant != null && !variant.isActive()) {
                throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE,
                        "Variant " + variant.getSku() + " is no longer available")
                        .withProperty("variantId", variant.getId().toString());
            }

            BigDecimal catalogPrice = variant != null ? variant.getPrice() : product.getBasePrice();
            if (catalogPrice.compareTo(item.getUnitPrice()) != 0) {
                if (priceChanged == null) {
                    priceChanged = new BusinessException(ErrorCode.PRICE_CHANGED,
                            "Catalog prices changed since the cart was built");
                }
                priceChanged.withViolation("items[" + item.getId() + "].unitPrice", "PRICE_CHANGED",
                        "تغيّر سعر المنتج إلى " + catalogPrice.toPlainString(), item.getUnitPrice());
            }

            lines.add(new RepricedLine(product, variant, item.getQuantity(),
                    Money.of(catalogPrice, currency), currency));
        }

        if (priceChanged != null) {
            throw priceChanged;
        }
        return lines;
    }

    /**
     * Spreads the order-level discount and tax across lines proportionally to their value.
     *
     * <p>Needed because a partial refund reverses specific lines, and an invoice has to show a
     * per-line tax figure that sums to the order total. The last line absorbs the rounding
     * remainder so the parts always add up to the whole — apportioning each line independently
     * leaves a piastre unaccounted for often enough to matter.
     */
    private void apportion(List<RepricedLine> lines, Money discount, Money tax, String currency) {
        Money subtotal = lines.stream()
                .map(RepricedLine::lineSubtotal)
                .reduce(Money.zero(currency), Money::plus);

        if (subtotal.isZero()) {
            return;
        }

        Money discountAllocated = Money.zero(currency);
        Money taxAllocated = Money.zero(currency);

        for (int i = 0; i < lines.size(); i++) {
            RepricedLine line = lines.get(i);
            boolean last = i == lines.size() - 1;

            Money lineDiscount;
            Money lineTax;

            if (last) {
                lineDiscount = discount.minus(discountAllocated);
                lineTax = tax.minus(taxAllocated);
            } else {
                BigDecimal share = line.lineSubtotal().amount()
                        .divide(subtotal.amount(), 10, RoundingMode.HALF_UP);
                lineDiscount = discount.times(share);
                lineTax = tax.times(share);
                discountAllocated = discountAllocated.plus(lineDiscount);
                taxAllocated = taxAllocated.plus(lineTax);
            }

            line.setDiscount(lineDiscount.atLeastZero());
            line.setTax(lineTax.atLeastZero());
        }
    }

    // -------------------------------------------------------------- addresses --

    private Map<String, Object> resolveShippingAddress(UUID userId, CheckoutRequest request) {
        if (request.hasSavedShippingAddress()) {
            if (userId == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "A guest cannot reference a saved address");
            }
            Address address = addresses.findByIdForUser(request.shippingAddressId(), userId)
                    .orElseThrow(() -> NotFoundException.of("Address", request.shippingAddressId()));
            return address.toSnapshot();
        }

        if (!request.hasInlineShippingAddress()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A shipping address is required")
                    .withViolation("shippingAddress", "REQUIRED", "عنوان الشحن مطلوب", null);
        }
        return snapshotOf(request.shippingAddress());
    }

    private Map<String, Object> resolveBillingAddress(UUID userId, CheckoutRequest request,
                                                      Map<String, Object> shippingSnapshot) {
        if (request.billingAddressId() == null || userId == null) {
            // Same as shipping unless the customer explicitly chose otherwise — the common case.
            return shippingSnapshot;
        }
        return addresses.findByIdForUser(request.billingAddressId(), userId)
                .map(Address::toSnapshot)
                .orElse(shippingSnapshot);
    }

    /** Freezes an inline address into the same map shape {@code Address.toSnapshot()} produces. */
    private Map<String, Object> snapshotOf(com.eshop.user.web.dto.AddressRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("recipientName", request.recipientName());
        snapshot.put("phone", request.phone());
        snapshot.put("countryCode", request.countryCodeOrDefault());
        snapshot.put("governorate", request.governorate());
        snapshot.put("city", request.city());
        snapshot.put("area", request.area());
        snapshot.put("street", request.street());
        snapshot.put("building", request.building());
        snapshot.put("apartment", request.apartment());
        snapshot.put("floor", request.floor());
        snapshot.put("landmark", request.landmark());
        snapshot.put("postalCode", request.postalCode());
        snapshot.values().removeIf(java.util.Objects::isNull);
        return snapshot;
    }

    /** A cart line re-priced against the catalog, with its share of discount and tax attached. */
    private static final class RepricedLine {

        private final Product product;
        private final ProductVariant variant;
        private final int quantity;
        private final Money unitPrice;
        private final String currency;

        private Money discount;
        private Money tax;

        RepricedLine(Product product, ProductVariant variant, int quantity,
                     Money unitPrice, String currency) {
            this.product = product;
            this.variant = variant;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.currency = currency;
            this.discount = Money.zero(currency);
            this.tax = Money.zero(currency);
        }

        Product product() {
            return product;
        }

        ProductVariant variant() {
            return variant;
        }

        int quantity() {
            return quantity;
        }

        Money lineSubtotal() {
            return unitPrice.times(quantity);
        }

        int shippingWeightGrams() {
            int perUnit = variant != null
                    ? variant.effectiveWeightGrams()
                    : (product.getWeightGrams() == null ? 0 : product.getWeightGrams());
            return perUnit * quantity;
        }

        void setDiscount(Money discount) {
            this.discount = discount;
        }

        void setTax(Money tax) {
            this.tax = tax;
        }

        OrderItem toOrderItem() {
            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setVariant(variant);
            item.setSku(variant != null ? variant.getSku() : product.getSku());
            item.setNameAr(product.getNameAr());
            item.setNameEn(product.getNameEn());
            item.setVariantLabelAr(variant == null ? null : variant.label("ar"));
            item.setVariantLabelEn(variant == null ? null : variant.label("en"));
            item.setImageUrl(variant != null && variant.getImageUrl() != null
                    ? variant.getImageUrl()
                    : product.primaryImage().map(image -> image.getUrl()).orElse(null));
            item.setUnitPrice(unitPrice.amount());
            item.setQuantity(quantity);
            item.setDiscountAmount(discount.amount());
            item.setTaxAmount(tax.amount());
            item.recomputeLineTotal();
            return item;
        }
    }
}
