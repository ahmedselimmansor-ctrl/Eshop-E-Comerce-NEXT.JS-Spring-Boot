package com.eshop.payment.domain;

/** The payment gateways this store integrates with. */
public enum PaymentProviderType {

    /** Cards, mobile wallets, and instalments, collected in a Paymob-hosted iframe. */
    PAYMOB,

    /**
     * Fawry reference codes. The customer receives a number and pays at any Fawry outlet, ATM, or
     * through their banking app — the dominant way to pay online without a card here.
     */
    FAWRY,

    /** Cash on delivery. No gateway; the courier collects and the order settles on delivery. */
    COD
}
