package com.bms.service;

import com.bms.entity.PaymentMethod;
import com.bms.entity.PaymentStatus;
import com.bms.entity.SalePayment;

import java.math.BigDecimal;
import java.util.List;

/**
 * Shared definition of "money that actually settled a sale".
 *
 * <p>A {@link PaymentMethod#CREDIT} SalePayment row is the invoice's own debt
 * placeholder created at sale time; it is not cash, card or store credit that
 * the shop collected. Counting it as settled tender let a void mint store
 * credit from unpaid debt (and double-refund a fully collected credit invoice).
 * Store credit is handled separately because it restores the customer's
 * liability instead of creating a new one.
 */
final class SettledTenderUtils {

    private SettledTenderUtils() {
    }

    static boolean isSettledNonCashTender(SalePayment payment) {
        if (payment == null || payment.getPaymentMethod() == null) {
            return false;
        }
        PaymentMethod method = payment.getPaymentMethod();
        if (method == PaymentMethod.CASH || method == PaymentMethod.CREDIT
                || method == PaymentMethod.STORE_CREDIT) {
            return false;
        }
        PaymentStatus status = payment.getPaymentStatus();
        return status == null
                || status == PaymentStatus.COMPLETED
                || status == PaymentStatus.PARTIALLY_REFUNDED
                || status == PaymentStatus.REFUNDED;
    }

    static BigDecimal settledNonCashTender(List<SalePayment> payments) {
        if (payments == null) {
            return BigDecimal.ZERO;
        }
        return payments.stream()
                .filter(SettledTenderUtils::isSettledNonCashTender)
                .map(SalePayment::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
