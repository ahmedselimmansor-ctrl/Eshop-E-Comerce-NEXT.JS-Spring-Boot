package com.eshop.payment.domain;

/** How the customer chose to pay, within a given provider. */
public enum PaymentMethod {

    CARD,

    /** Vodafone Cash, Orange Money, Etisalat Cash, and similar mobile wallets. */
    WALLET,

    /** Bank instalment plans offered through Paymob. */
    INSTALLMENT,

    /** Fawry reference paid in cash at an outlet or kiosk. */
    KIOSK,

    COD;

    /** Whether the method is settled before dispatch. COD is not — it settles at the door. */
    public boolean isPrepaid() {
        return this != COD;
    }
}
