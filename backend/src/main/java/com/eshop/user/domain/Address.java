package com.eshop.user.domain;

import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A saved delivery or billing address.
 *
 * <p>Shaped for Egypt rather than for a generic international form: governorate and area carry the
 * routing information couriers actually use, postal codes are optional because they are rarely
 * quoted, and building/floor/apartment/landmark are separate fields because "الدور الثالث، بجوار
 * صيدلية العزبي" is how an address is genuinely given here.
 *
 * <p>Orders never reference this row. {@link #toSnapshot()} freezes a copy into
 * {@code orders.shipping_address} at placement, so editing or deleting an address later cannot
 * rewrite where a past order was sent.
 */
@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor
public class Address extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Customer's own label: "المنزل", "الشغل". */
    @Column(name = "label", length = 40)
    private String label;

    @Column(name = "recipient_name", nullable = false, length = 120)
    private String recipientName;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode = "EG";

    @Column(name = "governorate", nullable = false, length = 60)
    private String governorate;

    @Column(name = "city", nullable = false, length = 80)
    private String city;

    @Column(name = "area", length = 80)
    private String area;

    @Column(name = "street", nullable = false, length = 160)
    private String street;

    @Column(name = "building", length = 40)
    private String building;

    @Column(name = "apartment", length = 40)
    private String apartment;

    @Column(name = "floor", length = 20)
    private String floor;

    @Column(name = "landmark", length = 160)
    private String landmark;

    @Column(name = "postal_code", length = 12)
    private String postalCode;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "is_default_shipping", nullable = false)
    private boolean defaultShipping = false;

    @Column(name = "is_default_billing", nullable = false)
    private boolean defaultBilling = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Single-line rendering for courier labels and order confirmation emails. */
    public String formatted() {
        StringBuilder out = new StringBuilder();
        appendIfPresent(out, street);
        appendIfPresent(out, building == null ? null : "عمارة " + building);
        appendIfPresent(out, floor == null ? null : "الدور " + floor);
        appendIfPresent(out, apartment == null ? null : "شقة " + apartment);
        appendIfPresent(out, landmark);
        appendIfPresent(out, area);
        appendIfPresent(out, city);
        appendIfPresent(out, governorate);
        return out.toString();
    }

    /**
     * Immutable copy stored on the order. Deliberately a plain map: the order must remain readable
     * even if this entity's shape changes in a later release.
     */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("recipientName", recipientName);
        snapshot.put("phone", phone);
        snapshot.put("countryCode", countryCode);
        snapshot.put("governorate", governorate);
        snapshot.put("city", city);
        snapshot.put("area", area);
        snapshot.put("street", street);
        snapshot.put("building", building);
        snapshot.put("apartment", apartment);
        snapshot.put("floor", floor);
        snapshot.put("landmark", landmark);
        snapshot.put("postalCode", postalCode);
        snapshot.put("latitude", latitude);
        snapshot.put("longitude", longitude);
        snapshot.put("formatted", formatted());
        snapshot.values().removeIf(java.util.Objects::isNull);
        return snapshot;
    }

    private static void appendIfPresent(StringBuilder out, String part) {
        if (part != null && !part.isBlank()) {
            if (!out.isEmpty()) {
                out.append("، ");
            }
            out.append(part.trim());
        }
    }
}
