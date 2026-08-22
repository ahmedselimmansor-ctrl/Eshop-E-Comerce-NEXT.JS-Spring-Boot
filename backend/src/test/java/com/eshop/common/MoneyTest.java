package com.eshop.common;

import com.eshop.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money arithmetic.
 *
 * <p>A plain unit test - no Spring, no database - because this is pure computation and it is where a
 * rounding mistake turns into a real customer being charged the wrong amount.
 */
class MoneyTest {

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("keeps two decimal places")
        void normalisesScale() {
            assertThat(Money.egp("10").amount()).isEqualByComparingTo("10.00");
            assertThat(Money.egp("10.005").amount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("defaults to EGP")
        void defaultsToEgp() {
            assertThat(Money.of(new BigDecimal("5.00")).currency()).isEqualTo("EGP");
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("adds and subtracts")
        void addsAndSubtracts() {
            assertThat(Money.egp("10.50").plus(Money.egp("4.50")).amount())
                    .isEqualByComparingTo("15.00");
            assertThat(Money.egp("10.50").minus(Money.egp("0.50")).amount())
                    .isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("multiplies by a line quantity")
        void multiplies() {
            assertThat(Money.egp("19.99").times(3).amount()).isEqualByComparingTo("59.97");
        }

        @Test
        @DisplayName("a percentage rounds to the nearest piastre")
        void percentageRounds() {
            // 14% VAT on 99.99 is 13.9986 - the customer is charged 14.00, not 13.99.
            assertThat(Money.egp("99.99").percentage(new BigDecimal("0.14")).amount())
                    .isEqualByComparingTo("14.00");
        }

        @Test
        @DisplayName("clamps a negative result to zero")
        void clampsToZero() {
            assertThat(Money.egp("5.00").minus(Money.egp("8.00")).atLeastZero().isZero()).isTrue();
        }
    }

    @Nested
    @DisplayName("currency safety")
    class CurrencySafety {

        @Test
        @DisplayName("refuses to add two different currencies")
        void refusesMixedCurrencies() {
            // Silently coercing here is how a store ends up adding dollars to pounds and shipping
            // an order for a fraction of its price.
            assertThatThrownBy(() -> Money.of(new BigDecimal("1.00"), "EGP")
                    .plus(Money.of(new BigDecimal("1.00"), "USD")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("comparison")
    class Comparison {

        @Test
        @DisplayName("compares by amount")
        void comparesByAmount() {
            assertThat(Money.egp("10.00").isGreaterThan(Money.egp("9.99"))).isTrue();
            assertThat(Money.egp("10.00").isLessThan(Money.egp("10.01"))).isTrue();
            assertThat(Money.egp("0.00").isZero()).isTrue();
            assertThat(Money.egp("0.01").isPositive()).isTrue();
        }

        @Test
        @DisplayName("equality ignores trailing-zero differences in scale")
        void equalityIsByValue() {
            assertThat(Money.egp("10.5")).isEqualTo(Money.egp("10.50"));
        }
    }
}
