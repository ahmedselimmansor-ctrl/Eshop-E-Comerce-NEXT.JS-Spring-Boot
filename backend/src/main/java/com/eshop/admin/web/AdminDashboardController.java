package com.eshop.admin.web;

import com.eshop.admin.service.DashboardService;
import com.eshop.common.web.PageResponse;
import com.eshop.common.web.RequestContext;
import com.eshop.config.AppProperties;
import com.eshop.order.domain.OrderStatus;
import com.eshop.order.service.OrderService;
import com.eshop.order.web.dto.OrderResponse;
import com.eshop.search.service.SearchAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Reporting for the admin dashboard. Read-only, and gated on {@code report:read} so a catalog
 * manager can see product performance without also seeing company revenue.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('report:read')")
@Tag(name = "Admin · Dashboard")
public class AdminDashboardController {

    private final DashboardService dashboard;
    private final OrderService orders;
    private final SearchAnalyticsService searchAnalytics;
    private final AppProperties properties;

    @GetMapping("/summary")
    @Operation(summary = "Headline metrics",
            description = "Each figure is paired with the equivalent preceding period. "
                    + "`changePercent` is null when the previous period was zero — render that as "
                    + "\"new\" rather than as a percentage.")
    public ResponseEntity<DashboardService.Summary> summary(
            @RequestParam(defaultValue = "7") int rangeDays) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(dashboard.summary(clampRange(rangeDays)));
    }

    @GetMapping("/revenue-series")
    @Operation(summary = "Revenue over time",
            description = "Granularity is chosen from the range — hourly up to 2 days, daily up to "
                    + "3 months, monthly beyond that.")
    public List<DashboardService.RevenuePoint> revenueSeries(
            @RequestParam(defaultValue = "30") int rangeDays) {
        return dashboard.revenueSeries(clampRange(rangeDays));
    }

    @GetMapping("/top-products")
    @Operation(summary = "Best sellers by units, with their revenue")
    public List<DashboardService.TopProduct> topProducts(
            @RequestParam(defaultValue = "30") int rangeDays,
            @RequestParam(defaultValue = "10") int limit) {
        return dashboard.topProducts(clampRange(rangeDays), Math.clamp(limit, 1, 50));
    }

    @GetMapping("/order-status-breakdown")
    @Operation(summary = "Orders by status", description = "Includes statuses with a count of zero.")
    public Map<OrderStatus, Long> orderStatusBreakdown(
            @RequestParam(defaultValue = "30") int rangeDays) {
        return dashboard.orderStatusBreakdown(clampRange(rangeDays));
    }

    @GetMapping("/low-stock")
    @Operation(summary = "SKUs at or below their reorder level",
            description = "Measured against sellable stock, so units held for pending orders do not "
                    + "mask a shortage.")
    public List<DashboardService.LowStockItem> lowStock(@RequestParam(defaultValue = "20") int limit) {
        return dashboard.lowStock(Math.clamp(limit, 1, 100));
    }

    @GetMapping("/recent-orders")
    @Operation(summary = "Latest orders, for the dashboard feed")
    public PageResponse<OrderResponse.Summary> recentOrders(
            @RequestParam(defaultValue = "10") int limit) {

        String locale = RequestContext.locale();

        return PageResponse.of(orders
                .adminSearch(null, null, null, null, null,
                        PageRequest.of(0, Math.clamp(limit, 1, 50),
                                Sort.by(Sort.Direction.DESC, "placedAt")))
                .map(order -> OrderResponse.summary(order, locale)));
    }

    @GetMapping("/zero-result-searches")
    @Operation(summary = "Searches that found nothing",
            description = "A merchandising report, not a bug report: repeated zero-result terms are "
                    + "demand the catalog is not serving.")
    public List<SearchAnalyticsService.ZeroResultQuery> zeroResultSearches(
            @RequestParam(defaultValue = "30") int rangeDays,
            @RequestParam(defaultValue = "20") int limit) {
        return searchAnalytics.zeroResultQueries(clampRange(rangeDays), Math.clamp(limit, 1, 100));
    }

    /** Bounded so a hand-typed range cannot turn the dashboard into a full-table scan. */
    private int clampRange(int rangeDays) {
        return Math.clamp(rangeDays, 1, properties.getAdmin().getDashboardMaxRangeDays());
    }
}
