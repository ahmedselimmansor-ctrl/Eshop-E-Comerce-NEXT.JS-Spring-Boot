package com.eshop.inventory.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.config.AppProperties;
import com.eshop.inventory.domain.InventoryItem;
import com.eshop.inventory.domain.InventoryMovement;
import com.eshop.inventory.domain.StockReservation;
import com.eshop.inventory.repository.InventoryItemRepository;
import com.eshop.inventory.repository.InventoryMovementRepository;
import com.eshop.inventory.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single owner of stock levels. No other class writes to {@code inventory_items}.
 *
 * <p>The reservation lifecycle it implements is:
 * <pre>
 *   checkout           payment captured
 *      │                      │
 *      ▼                      ▼
 *   reserve() ──────────► commit()          on_hand -= qty, reserved -= qty
 *      │
 *      ├── payment failed / order cancelled ──► release()   reserved -= qty
 *      └── 30 min with no payment ───────────► releaseExpired()
 * </pre>
 * Stock is never decremented at checkout time, only held. An order that is never paid must not
 * destroy inventory, and un-destroying it after the fact is a compensating write that will
 * eventually be got wrong.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final String REF_ORDER = "ORDER";
    private static final String REF_STOCK_TAKE = "STOCK_TAKE";

    private final InventoryItemRepository items;
    private final InventoryMovementRepository movements;
    private final StockReservationRepository reservations;
    private final AppProperties properties;

    // ---------------------------------------------------------- availability --

    @Transactional(readOnly = true)
    public int available(UUID productId, UUID variantId) {
        return items.findForSku(productId, variantId).stream()
                .mapToInt(InventoryItem::available)
                .sum();
    }

    /**
     * Bulk availability for a cart or a product listing.
     *
     * <p>One query for the whole set rather than one per line: a twenty-line cart otherwise issues
     * twenty round trips on every render.
     */
    @Transactional(readOnly = true)
    public Map<SkuRef, Integer> availabilityFor(List<SkuRef> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        List<UUID> productIds = skus.stream().map(SkuRef::productId).distinct().toList();

        Map<SkuRef, Integer> availability = new HashMap<>();
        for (InventoryItem item : items.findByProductIds(productIds)) {
            SkuRef ref = new SkuRef(
                    item.getProduct().getId(),
                    item.getVariant() == null ? null : item.getVariant().getId());
            availability.merge(ref, item.available(), Integer::sum);
        }
        // SKUs with no stock row at all are genuinely unavailable, not absent from the answer.
        skus.forEach(sku -> availability.putIfAbsent(sku, 0));
        return availability;
    }

    /**
     * Total sellable units per product, for a listing.
     *
     * <p>Not {@link #availabilityFor(List)}: that keys on (product, variant) so a caller asking about
     * a variable product with the variant left null gets nothing back. This one is the per-product
     * total, which is the figure a catalog list actually shows.
     *
     * <p>Products with no stock row map to zero rather than being absent, so a caller can render the
     * result directly without treating "missing" and "none" as different cases.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> availabilityByProduct(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> availability = new HashMap<>();
        for (Object[] row : items.sumAvailableByProduct(productIds)) {
            availability.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        productIds.forEach(id -> availability.putIfAbsent(id, 0));
        return availability;
    }

    // ------------------------------------------------------------ reservation --

    /**
     * Holds stock for an order.
     *
     * <p>Must run inside the caller's transaction ({@code MANDATORY}): the reservation and the order
     * row have to commit or roll back together. If they could commit independently, a failure
     * between them would either strand held stock with no order to release it, or create an order
     * for units nobody is holding.
     *
     * <p>All rows are locked up front with {@code SELECT … FOR UPDATE} in a deterministic id order,
     * which serialises concurrent checkouts for the same SKU and prevents deadlock between two
     * checkouts that share two SKUs.
     *
     * @throws BusinessException {@code OUT_OF_STOCK}, with per-line detail naming what is short
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockReservation> reserve(UUID orderId, List<Line> lines) {
        Instant expiresAt = Instant.now().plusSeconds(
                properties.getCheckout().getReservationMinutes() * 60L);

        // Resolve every line to its candidate stock rows before locking, so the lock set is known
        // in one pass and can be ordered.
        Map<Line, List<InventoryItem>> candidates = new LinkedHashMap<>();
        List<UUID> lockIds = new ArrayList<>();

        for (Line line : lines) {
            List<InventoryItem> rows = items.findForSku(line.productId(), line.variantId());
            if (rows.isEmpty()) {
                throw outOfStock(line, 0);
            }
            candidates.put(line, rows);
            rows.forEach(row -> lockIds.add(row.getId()));
        }

        Map<UUID, InventoryItem> locked = new HashMap<>();
        items.lockAllById(lockIds.stream().distinct().sorted().toList())
                .forEach(item -> locked.put(item.getId(), item));

        List<StockReservation> created = new ArrayList<>();

        for (Map.Entry<Line, List<InventoryItem>> entry : candidates.entrySet()) {
            Line line = entry.getKey();

            // Re-read post-lock: the values seen before the lock may already be stale.
            List<InventoryItem> rows = entry.getValue().stream()
                    .map(row -> locked.getOrDefault(row.getId(), row))
                    .sorted(Comparator.comparingInt(row -> row.getWarehouse().getPriority()))
                    .toList();

            int totalAvailable = rows.stream().mapToInt(InventoryItem::available).sum();
            if (totalAvailable < line.quantity()) {
                throw outOfStock(line, totalAvailable);
            }

            // Greedy allocation down the warehouse priority order; splits across warehouses only
            // when no single location can cover the line.
            int remaining = line.quantity();
            for (InventoryItem row : rows) {
                if (remaining == 0) {
                    break;
                }
                int take = Math.min(remaining, row.available());
                if (take <= 0) {
                    continue;
                }

                row.reserve(take);
                items.save(row);
                movements.save(InventoryMovement.of(
                        row, InventoryMovement.Type.RESERVATION, -take, REF_ORDER, orderId, null));

                created.add(reservations.save(
                        StockReservation.forOrder(orderId, row, take, expiresAt)));
                remaining -= take;
            }
        }

        log.info("stock_reserved order={} lines={} reservations={}", orderId, lines.size(), created.size());
        return created;
    }

    /**
     * Turns holds into sales once payment is captured. This is the moment stock actually decreases.
     *
     * <p>Idempotent: a duplicate webhook delivery finds no {@code HELD} rows and does nothing, so a
     * provider retry cannot double-decrement.
     */
    @Transactional
    public void commitReservations(UUID orderId) {
        List<StockReservation> held = reservations.findByOrderIdAndStatus(orderId, StockReservation.Status.HELD);
        if (held.isEmpty()) {
            log.debug("stock_commit_noop order={} — no held reservations", orderId);
            return;
        }

        for (StockReservation reservation : held) {
            InventoryItem item = items.lockById(reservation.getInventoryItem().getId())
                    .orElseThrow(() -> NotFoundException.of("InventoryItem",
                            reservation.getInventoryItem().getId()));

            item.commitReservation(reservation.getQuantity());
            items.save(item);

            movements.save(InventoryMovement.of(
                    item, InventoryMovement.Type.SALE, -reservation.getQuantity(),
                    REF_ORDER, orderId, null));

            reservation.commit();
            reservations.save(reservation);
        }
        log.info("stock_committed order={} reservations={}", orderId, held.size());
    }

    /** Returns holds to sellable stock: payment failed, or the order was cancelled. */
    @Transactional
    public void releaseReservations(UUID orderId) {
        List<StockReservation> held = reservations.findByOrderIdAndStatus(orderId, StockReservation.Status.HELD);
        held.forEach(reservation -> releaseOne(reservation, orderId));
        if (!held.isEmpty()) {
            log.info("stock_released order={} reservations={}", orderId, held.size());
        }
    }

    /**
     * Sweeps holds whose checkout was abandoned. Called on a schedule under a cluster-wide lock.
     *
     * @return how many holds were released
     */
    @Transactional
    public int releaseExpired(int batchSize) {
        List<StockReservation> expired = reservations.findExpired(Instant.now(), PageRequest.of(0, batchSize));
        expired.forEach(reservation -> releaseOne(reservation, reservation.getOrderId()));

        if (!expired.isEmpty()) {
            log.info("stock_reservations_expired released={}", expired.size());
        }
        return expired.size();
    }

    private void releaseOne(StockReservation reservation, UUID orderId) {
        InventoryItem item = items.lockById(reservation.getInventoryItem().getId())
                .orElse(reservation.getInventoryItem());

        item.release(reservation.getQuantity());
        items.save(item);

        movements.save(InventoryMovement.of(
                item, InventoryMovement.Type.RELEASE, reservation.getQuantity(),
                REF_ORDER, orderId, null));

        reservation.release();
        reservations.save(reservation);
    }

    /** Puts returned goods back on the shelf. */
    @Transactional
    public void restock(UUID productId, UUID variantId, int quantity, UUID referenceId, UUID actorId) {
        InventoryItem item = items.findForSku(productId, variantId).stream()
                .min(Comparator.comparingInt(row -> row.getWarehouse().getPriority()))
                .orElseThrow(() -> NotFoundException.of("InventoryItem", productId));

        InventoryItem locked = items.lockById(item.getId()).orElse(item);
        locked.receive(quantity);
        items.save(locked);

        movements.save(InventoryMovement.of(
                locked, InventoryMovement.Type.RETURN, quantity, "RETURN", referenceId, actorId));
    }

    // ------------------------------------------------------------ admin edits --

    /**
     * Sets an absolute on-hand count after a physical stock take.
     *
     * <p>Absolute rather than a delta because that is how a stock take works — the counter reports
     * what is on the shelf, not how it differs from the system. The ledger still records the signed
     * difference so the correction is auditable.
     */
    @Transactional
    public InventoryItem adjust(UUID itemId, int newOnHand, String reason, UUID actorId) {
        InventoryItem item = items.lockById(itemId)
                .orElseThrow(() -> NotFoundException.of("InventoryItem", itemId));

        int before = item.getQuantityOnHand();
        try {
            item.adjustTo(newOnHand);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.CONFLICT, e.getMessage())
                    .withViolation("quantity", "BELOW_RESERVED",
                            "لا يمكن تقليل الكمية أقل من الكمية المحجوزة", newOnHand);
        }
        items.save(item);

        InventoryMovement movement = InventoryMovement.of(
                item, InventoryMovement.Type.ADJUSTMENT, newOnHand - before,
                REF_STOCK_TAKE, itemId, actorId);
        movement.setNote(reason);
        movements.save(movement);

        log.info("inventory_adjusted item={} from={} to={} actor={}", itemId, before, newOnHand, actorId);
        return item;
    }

    @Transactional
    public InventoryItem receive(UUID itemId, int quantity, String note, UUID actorId) {
        InventoryItem item = items.lockById(itemId)
                .orElseThrow(() -> NotFoundException.of("InventoryItem", itemId));

        item.receive(quantity);
        items.save(item);

        InventoryMovement movement = InventoryMovement.of(
                item, InventoryMovement.Type.RECEIPT, quantity, "PURCHASE_ORDER", null, actorId);
        movement.setNote(note);
        movements.save(movement);

        return item;
    }

    // --------------------------------------------------------------- helpers --

    private BusinessException outOfStock(Line line, int available) {
        return new BusinessException(ErrorCode.OUT_OF_STOCK,
                "Insufficient stock for product " + line.productId() + ": requested "
                        + line.quantity() + ", available " + available)
                .withProperty("productId", line.productId())
                .withProperty("variantId", line.variantId())
                .withProperty("availableQuantity", available)
                .withViolation("quantity", "MAX_AVAILABLE",
                        available == 0 ? "المنتج غير متوفر حالياً" : "المتاح " + available + " فقط",
                        line.quantity());
    }

    /** A SKU plus the quantity being reserved. */
    public record Line(UUID productId, UUID variantId, int quantity) {
    }

    /** Identifies a purchasable SKU: a product, optionally narrowed to one variant. */
    public record SkuRef(UUID productId, UUID variantId) {
    }
}
