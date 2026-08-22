package com.eshop.admin.service;

import com.eshop.catalog.repository.ProductRepository;
import com.eshop.common.money.Money;
import com.eshop.config.RedisConfig;
import com.eshop.inventory.repository.InventoryItemRepository;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.repository.OrderRepository;
import com.eshop.payment.repository.PaymentRepository;
import com.eshop.review.domain.Review;
import com.eshop.review.repository.ReviewRepository;
import com.eshop.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates for the admin dashboard.
 *
 * <p>Every figure is compared against the equivalent preceding window, because an absolute number
 * on its own tells an operator nothing — "EGP 420,000 this week" only means something next to last
 * week's.
 *
 * <p>Cached for a minute. These are several aggregate scans, the dashboard auto-refreshes, and a
 * revenue figure that is sixty seconds stale is not a problem worth running the queries on every
 * poll for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final OrderRepository orders;
    private final ProductRepository products;
    private final UserRepository users;
    private final ReviewRepository reviews;
    private final PaymentRepository payments;
    private final InventoryItemRepository inventory;

    @Cacheable(value = RedisConfig.CACHE_DASHBOARD, key = "'summary:' + #rangeDays")
    @Transactional(readOnly = true)
    public Summary summary(int rangeDays) {
        Instant now = Instant.now();
        Instant from = now.minus(rangeDays, ChronoUnit.DAYS);
        // The immediately preceding window of the same length — the only fair comparison.
        Instant previousFrom = from.minus(rangeDays, ChronoUnit.DAYS);

        BigDecimal revenue = orders.revenueBetween(from, now);
        BigDecimal previousRevenue = orders.revenueBetween(previousFrom, from);

        long orderCount = orders.countBetween(from, now);
        long previousOrderCount = orders.countBetween(previousFrom, from);

        long newCustomers = users.countRegisteredSince(from);
        long previousNewCustomers = users.countRegisteredSince(previousFrom) - newCustomers;

        // Average order value, guarded against a zero-order window.
        BigDecimal averageOrderValue = orderCount == 0
                ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP);

        BigDecimal previousAov = previousOrderCount == 0
                ? BigDecimal.ZERO
                : previousRevenue.divide(BigDecimal.valueOf(previousOrderCount), 2, RoundingMode.HALF_UP);

        return new Summary(
                new Metric(Money.of(revenue), Money.of(previousRevenue), percentChange(revenue, previousRevenue)),
                new CountMetric(orderCount, previousOrderCount, percentChange(orderCount, previousOrderCount)),
                new Metric(Money.of(averageOrderValue), Money.of(previousAov),
                        percentChange(averageOrderValue, previousAov)),
                new CountMetric(newCustomers, previousNewCustomers,
                        percentChange(newCustomers, previousNewCustomers)),
                orders.countByStatus(OrderStatus.PENDING_PAYMENT),
                orders.countByStatus(OrderStatus.PAID) + orders.countByStatus(OrderStatus.PROCESSING),
                reviews.countByStatus(Review.Status.PENDING),
                inventory.countOutOfStock(),
                payments.countFailedSince(from));
    }

    /**
     * Revenue over time for the dashboard chart.
     *
     * <p>Granularity is chosen from the range rather than left to the caller: a year of daily points
     * is 365 values on a chart 600 pixels wide, and a day of monthly points is one bar.
     */
    @Cacheable(value = RedisConfig.CACHE_DASHBOARD, key = "'series:' + #rangeDays")
    @Transactional(readOnly = true)
    public List<RevenuePoint> revenueSeries(int rangeDays) {
        String granularity = rangeDays <= 2 ? "hour" : (rangeDays <= 92 ? "day" : "month");
        Instant now = Instant.now();

        return orders.revenueSeries(now.minus(rangeDays, ChronoUnit.DAYS), now, granularity).stream()
                .map(point -> new RevenuePoint(
                        point.getBucket(), Money.of(point.getRevenue()), point.getOrders()))
                .toList();
    }

    @Cacheable(value = RedisConfig.CACHE_DASHBOARD, key = "'top:' + #rangeDays + ':' + #limit")
    @Transactional(readOnly = true)
    public List<TopProduct> topProducts(int rangeDays, int limit) {
        Instant now = Instant.now();

        return orders.topProducts(now.minus(rangeDays, ChronoUnit.DAYS), now, limit).stream()
                .map(product -> new TopProduct(
                        product.getProductId(),
                        product.getNameAr(),
                        product.getImageUrl(),
                        product.getUnitsSold(),
                        Money.of(product.getRevenue())))
                .toList();
    }

    @Cacheable(value = RedisConfig.CACHE_DASHBOARD, key = "'statuses:' + #rangeDays")
    @Transactional(readOnly = true)
    public Map<OrderStatus, Long> orderStatusBreakdown(int rangeDays) {
        Instant now = Instant.now();

        Map<OrderStatus, Long> breakdown = new LinkedHashMap<>();
        // Every status is present, including the zeroes — a chart that silently omits "CANCELLED"
        // reads as "no cancellations" rather than "no data".
        for (OrderStatus status : OrderStatus.values()) {
            breakdown.put(status, 0L);
        }
        orders.statusBreakdown(now.minus(rangeDays, ChronoUnit.DAYS), now)
                .forEach(row -> breakdown.put(row.getStatus(), row.getTotal()));

        return breakdown;
    }

    @Transactional(readOnly = true)
    public List<LowStockItem> lowStock(int limit) {
        return inventory.findLowStock(null, PageRequest.of(0, limit)).stream()
                .map(item -> new LowStockItem(
                        item.getId(),
                        item.getProduct().getId(),
                        item.getProduct().getNameAr(),
                        item.sku(),
                        item.available(),
                        item.getReorderLevel()))
                .toList();
    }

    // ---------------------------------------------------------------- helpers --

    /**
     * Percentage change, with the two awkward cases handled explicitly.
     *
     * <p>Growth from zero is not "infinite percent" — it is reported as {@code null} so the UI can
     * show "new" instead of a meaningless number.
     */
    private static Double percentChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) {
            return current != null && current.signum() > 0 ? null : 0.0;
        }
        return current.subtract(previous)
                .divide(previous, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private static Double percentChange(long current, long previous) {
        return percentChange(BigDecimal.valueOf(current), BigDecimal.valueOf(Math.max(0, previous)));
    }

    /**
     * @param changePercent {@code null} when the previous period was zero — render as "new"
     */
    public record Metric(Money current, Money previous, Double changePercent) {
    }

    public record CountMetric(long current, long previous, Double changePercent) {
    }

    public record Summary(Metric revenue,
                          CountMetric orders,
                          Metric averageOrderValue,
                          CountMetric newCustomers,
                          long awaitingPayment,
                          long awaitingFulfilment,
                          long pendingReviews,
                          long outOfStockSkus,
                          long failedPayments) {
    }

    public record RevenuePoint(Instant at, Money revenue, long orders) {
    }

    public record TopProduct(UUID productId, String name, String imageUrl, long unitsSold, Money revenue) {
    }

    public record LowStockItem(UUID inventoryItemId, UUID productId, String productName,
                               String sku, int available, int reorderLevel) {
    }
}
