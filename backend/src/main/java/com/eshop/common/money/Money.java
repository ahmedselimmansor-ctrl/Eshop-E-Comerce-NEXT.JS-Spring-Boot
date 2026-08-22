package com.eshop.common.money;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A monetary amount with its currency.
 *
 * <p>Every amount is normalised to two decimal places with {@link RoundingMode#HALF_UP} on
 * construction, so {@code 10.005} and {@code 10.01} are the same value and equality behaves the way
 * an accountant expects. The amount is serialised as a JSON <em>string</em> to keep it out of IEEE
 * 754 double territory once it reaches JavaScript:
 *
 * <pre>{ "amount": "1299.00", "currency": "EGP" }</pre>
 *
 * <p>Arithmetic across differing currencies throws rather than silently coercing — mixing EGP and
 * USD in one total is a bug, not a rounding concern.
 */
public record Money(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency
) implements Comparable<Money>, Serializable {

    public static final int SCALE = 2;
    public static final String DEFAULT_CURRENCY = "EGP";

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO 4217 code: " + currency);
        }
        currency = currency.toUpperCase();
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount, DEFAULT_CURRENCY);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money egp(String amount) {
        return new Money(new BigDecimal(amount), DEFAULT_CURRENCY);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Money zero() {
        return zero(DEFAULT_CURRENCY);
    }

    /** Null-safe wrapper for values read straight out of a nullable column. */
    public static Money ofNullable(BigDecimal amount, String currency) {
        return amount == null ? null : new Money(amount, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money times(int factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    /** Applies a rate such as a 0.14 VAT rate or a 0.30 discount. */
    public Money percentage(BigDecimal rate) {
        return new Money(amount.multiply(rate), currency);
    }

    /** Clamps to zero — used where a discount must never push a line negative. */
    public Money atLeastZero() {
        return isNegative() ? zero(currency) : this;
    }

    public Money min(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) <= 0 ? this : other;
    }

    public Money max(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) >= 0 ? this : other;
    }

    @JsonIgnore
    public boolean isZero() {
        return amount.signum() == 0;
    }

    @JsonIgnore
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    @JsonIgnore
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) < 0;
    }

    /** Minor units (piastres for EGP) — the unit Paymob and Fawry expect on the wire. */
    @JsonIgnore
    public long toMinorUnits() {
        return amount.movePointRight(SCALE).longValueExact();
    }

    public static Money fromMinorUnits(long minorUnits, String currency) {
        return new Money(BigDecimal.valueOf(minorUnits).movePointLeft(SCALE), currency);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
