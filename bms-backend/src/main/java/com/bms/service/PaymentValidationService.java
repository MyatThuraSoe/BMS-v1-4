package com.bms.service;

import com.bms.dto.request.PaymentValidationRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.dto.response.PaymentValidationResponse;
import com.bms.dto.response.SalePaymentResponse;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.PaymentMethod;
import com.bms.entity.PaymentStatus;
import com.bms.entity.Sale;
import com.bms.entity.SalePayment;
import com.bms.entity.ShiftType;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for "may this payment method be used on this shift".
 *
 * <p>Sale creation, checkout pre-validation, refunds and AR payments all call
 * through here so the POS preview and the write path can never disagree.
 */
@Service
@Transactional
public class PaymentValidationService {

    @Autowired
    private CashShiftRepository cashShiftRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SalePaymentRepository salePaymentRepository;

    @Autowired
    private CashDrawerSessionService cashDrawerSessionService;

    public CashShift requireOpenShift(Long userId) {
        return cashShiftRepository.findByCashierIdAndStatus(userId, "OPEN")
                .orElseThrow(() -> new BusinessException(
                        "NO_OPEN_SHIFT: Please open a shift before recording transactions."));
    }

    public CashShift findOpenShift(Long userId) {
        return cashShiftRepository.findByCashierIdAndStatus(userId, "OPEN").orElse(null);
    }

    public PaymentValidationResponse validateForUser(Long userId, PaymentValidationRequest request) {
        CashShift shift = findOpenShift(userId);
        if (shift == null) {
            PaymentValidationResponse response = new PaymentValidationResponse();
            response.setValid(false);
            response.setCode("NO_OPEN_SHIFT");
            response.setMessage("Please open a shift before checkout.");
            response.setCashAllowed(false);
            return response;
        }
        return validate(shift, request != null ? request.getPayments() : null);
    }

    public PaymentValidationResponse validate(CashShift shift, List<SalePaymentRequest> payments) {
        PaymentValidationResponse response = new PaymentValidationResponse();
        response.setValid(true);
        response.setShiftId(shift.getId());
        response.setShiftType(shift.getShiftType().name());

        CashDrawerSession session = shift.getDrawerSession();
        boolean drawerOpen = session != null
                && session.getStatus() == com.bms.entity.DrawerSessionStatus.OPEN;
        boolean cashAllowed = shift.getShiftType() == ShiftType.CASH_DRAWER && drawerOpen;
        response.setCashAllowed(cashAllowed);
        response.setHasActiveDrawerSession(drawerOpen);
        if (session != null) {
            response.setDrawerSessionId(session.getId());
            if (session.getDrawer() != null) {
                response.setDrawerName(session.getDrawer().getName());
            }
        }

        if (payments == null || payments.isEmpty()) {
            return response;
        }

        for (SalePaymentRequest payment : payments) {
            PaymentMethod method;
            try {
                method = parseMethod(payment.getMethod());
            } catch (BusinessException ex) {
                response.setValid(false);
                response.setCode("INVALID_PAYMENT_METHOD");
                response.setMessage(ex.getMessage());
                return response;
            }
            if (method == PaymentMethod.CASH && !cashAllowed) {
                response.setValid(false);
                response.setCashAllowed(false);
                if (shift.getShiftType() == ShiftType.CASH_DRAWER) {
                    response.setCode("DRAWER_SESSION_REQUIRED");
                    response.setMessage("Cash payments require an open cash drawer session.");
                } else {
                    response.setCode("CASH_NOT_ALLOWED_FOR_SHIFT");
                    response.setMessage("This is a Non-Cash Shift. Cash payments are not allowed.");
                }
                return response;
            }
        }
        return response;
    }

    /**
     * Throws a BusinessException if the requested payment lines cannot be
     * completed on this shift. Called before any sale or stock row is written.
     */
    public void verifyForSale(CashShift shift, List<SalePaymentRequest> payments,
                              BigDecimal totalAmount, Sale.SaleType saleType) {
        PaymentValidationResponse validation = validate(shift, payments);
        if (!validation.isValid()) {
            throw new BusinessException(validation.getCode() + ": " + validation.getMessage());
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (SalePaymentRequest payment : payments) {
            if (payment.getAmount() == null || payment.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("PAYMENT_INVALID: Every payment amount must be greater than zero.");
            }
            PaymentMethod method = parseMethod(payment.getMethod());
            if (saleType != Sale.SaleType.CREDIT && method == PaymentMethod.CREDIT) {
                throw new BusinessException(
                        "PAYMENT_INVALID: A credit payment line is only allowed on a credit sale.");
            }
            if (saleType == Sale.SaleType.CREDIT && method != PaymentMethod.CREDIT) {
                throw new BusinessException(
                        "PAYMENT_INVALID: A credit sale must be recorded as a credit payment.");
            }
            sum = sum.add(payment.getAmount());
        }

        if (sum.compareTo(totalAmount) != 0) {
            throw new BusinessException("PAYMENT_TOTAL_MISMATCH: Payments total " + sum
                    + " does not match the sale total " + totalAmount + ".");
        }
    }

    /**
     * Legacy requests have no payment list. They represent a single cash
     * payment for a cash sale, or a single credit line for a credit invoice.
     */
    public List<SalePaymentRequest> resolvePayments(List<SalePaymentRequest> requested,
                                                    BigDecimal totalAmount,
                                                    Sale.SaleType saleType) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        List<SalePaymentRequest> fallback = new ArrayList<>();
        SalePaymentRequest payment = new SalePaymentRequest();
        payment.setMethod(saleType == Sale.SaleType.CREDIT
                ? PaymentMethod.CREDIT.name() : PaymentMethod.CASH.name());
        payment.setAmount(totalAmount);
        fallback.add(payment);
        return fallback;
    }

    /**
     * Persists payment lines after the sale row exists, creating one drawer
     * movement per cash line.
     */
    public List<SalePayment> saveSalePayments(Sale sale, CashShift shift,
                                              List<SalePaymentRequest> payments) {
        List<SalePayment> saved = new ArrayList<>();
        CashDrawerSession session = shift.getDrawerSession();
        User performer = userRepository.findById(sale.getCashierId()).orElse(null);

        for (SalePaymentRequest request : payments) {
            PaymentMethod method = parseMethod(request.getMethod());
            SalePayment payment = new SalePayment();
            payment.setSale(sale);
            payment.setPaymentMethod(method);
            payment.setAmount(request.getAmount());
            payment.setExternalReference(request.getExternalReference());
            payment.setPaymentStatus(method == PaymentMethod.CREDIT
                    ? PaymentStatus.PENDING : PaymentStatus.COMPLETED);

            if (method == PaymentMethod.CASH) {
                CashDrawerMovement movement = cashDrawerSessionService.recordFinancialMovement(
                        session,
                        DrawerMovementType.CASH_SALE,
                        request.getAmount(),
                        "SALE",
                        sale.getId(),
                        performer,
                        shift,
                        "Cash sale " + sale.getInvoiceNumber());
                payment.setDrawerSession(session);
                payment.setDrawerMovement(movement);
            }

            SalePayment persisted = salePaymentRepository.save(payment);
            sale.getPayments().add(persisted);
            saved.add(persisted);
        }
        return saved;
    }

    public PaymentMethod parseMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("PAYMENT_INVALID: Payment method is required.");
        }
        String normalised = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        switch (normalised) {
            case "KBZPAY":
            case "KBZ_PAY":
            case "WAVEPAY":
            case "WAVE_PAY":
            case "WAVE_MONEY":
            case "WALLET":
                return PaymentMethod.MOBILE_WALLET;
            case "BANK":
            case "BANK_TRANSFER":
                return PaymentMethod.BANK_TRANSFER;
            case "CREDIT_SALE":
                return PaymentMethod.CREDIT;
            default:
                try {
                    return PaymentMethod.valueOf(normalised);
                } catch (IllegalArgumentException ex) {
                    throw new BusinessException("PAYMENT_INVALID: Unknown payment method: " + raw);
                }
        }
    }

    public SalePaymentResponse toResponse(SalePayment payment) {
        SalePaymentResponse response = new SalePaymentResponse();
        response.setId(payment.getId());
        response.setPaymentMethod(payment.getPaymentMethod() != null
                ? payment.getPaymentMethod().name() : null);
        response.setAmount(payment.getAmount());
        response.setExternalReference(payment.getExternalReference());
        response.setPaymentStatus(payment.getPaymentStatus() != null
                ? payment.getPaymentStatus().name() : null);
        if (payment.getDrawerSession() != null) {
            response.setDrawerSessionId(payment.getDrawerSession().getId());
        }
        if (payment.getDrawerMovement() != null) {
            response.setDrawerMovementId(payment.getDrawerMovement().getId());
        }
        response.setPaidAt(payment.getPaidAt());
        return response;
    }
}
