package com.bms.service;

import com.bms.dto.request.PurchasePaymentRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.dto.response.PayablesAgingResponse;
import com.bms.dto.response.PaymentValidationResponse;
import com.bms.dto.response.PurchasePaymentResponse;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Purchase;
import com.bms.entity.PurchasePayment;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.PurchasePaymentRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O3: accounts payable. Records supplier payments against purchase invoices
 * (mirroring {@link ArService} for receivables) and derives the purchase
 * payment status from recorded payments and returned goods. The purchase row
 * is PESSIMISTIC_WRITE locked so concurrent payments can never overpay the
 * outstanding balance.
 */
@Service
@Transactional
public class PurchasePaymentService {

    @Autowired
    private ShopTimeService shopTimeService;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private PurchasePaymentRepository purchasePaymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private PaymentValidationService paymentValidationService;

    @Autowired
    private CashDrawerSessionService cashDrawerSessionService;

    @Autowired
    private PeriodGuard periodGuard;

    /**
     * Derived status: payable = totalAmount - returnedAmount. Nothing owed
     * (fully returned or zero invoice) counts as PAID; otherwise the recorded
     * payments decide between PENDING, PARTIAL and PAID.
     */
    public static Purchase.PaymentStatus deriveStatus(BigDecimal payable, BigDecimal paid) {
        BigDecimal owed = payable != null ? payable : BigDecimal.ZERO;
        BigDecimal settled = paid != null ? paid : BigDecimal.ZERO;
        if (owed.compareTo(BigDecimal.ZERO) <= 0 || settled.compareTo(owed) >= 0) {
            return Purchase.PaymentStatus.PAID;
        }
        return settled.signum() > 0 ? Purchase.PaymentStatus.PARTIAL : Purchase.PaymentStatus.PENDING;
    }

    /** Recomputes and stores the derived status after totals/returns/payments change. */
    public Purchase.PaymentStatus refreshPaymentStatus(Purchase purchase) {
        BigDecimal paid = paidAmount(purchase.getId());
        Purchase.PaymentStatus status = deriveStatus(purchase.getPayableAmount(), paid);
        purchase.setPaymentStatus(status);
        return status;
    }

    public BigDecimal paidAmount(Long purchaseId) {
        BigDecimal sum = purchasePaymentRepository.sumAmountByPurchaseId(purchaseId);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    // ---- Reads ------------------------------------------------------------

    public List<PurchasePaymentResponse> getPurchasePayments(Long purchaseId) {
        if (!purchaseRepository.existsById(purchaseId)) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseId);
        }
        return purchasePaymentRepository.findByPurchaseIdOrderByPaymentDateAsc(purchaseId)
                .stream()
                .map(this::toPaymentResponse)
                .toList();
    }

    /**
     * Payables aging by supplier, bucketed on the purchase date age as of the
     * given date. Outstanding is invoice-currency payable minus recorded
     * payments, converted to base currency at the purchase exchange rate so
     * mixed-currency suppliers can be summed meaningfully.
     */
    @Transactional(readOnly = true)
    public PayablesAgingResponse getPayablesAging(LocalDate asOfDate) {
        LocalDate asOf = asOfDate != null ? asOfDate : shopTimeService.today();
        List<Purchase> purchases = purchaseRepository.findActivePurchases(Pageable.unpaged()).getContent();

        Map<Long, BigDecimal> paidByPurchase = new HashMap<>();
        if (!purchases.isEmpty()) {
            List<Long> ids = purchases.stream().map(Purchase::getId).toList();
            for (Object[] row : purchasePaymentRepository.sumAmountsByPurchaseIds(ids)) {
                paidByPurchase.put(((Number) row[0]).longValue(), (BigDecimal) row[1]);
            }
        }

        Map<Long, PayablesAgingResponse.SupplierAging> bySupplier = new LinkedHashMap<>();
        PayablesAgingResponse.SupplierAging totals = new PayablesAgingResponse.SupplierAging();
        totals.setSupplierName("Total");
        for (Purchase purchase : purchases) {
            // Skip ORDERED (unreceived) POs — nothing received, no payable yet.
            // The GL journal only creates AP on receipt, so ORDERED POs must not
            // appear in aging.
            if (purchase.getReceivingStatus() == Purchase.ReceivingStatus.ORDERED) {
                continue;
            }
            BigDecimal paid = paidByPurchase.getOrDefault(purchase.getId(), BigDecimal.ZERO);
            // A PARTIAL receipt means only part of the goods has landed, so only that
            // share of the payable is outstanding yet. ORDERED POs are skipped above;
            // RECEIVED POs are fully outstanding; PARTIAL POs are scaled by the
            // received share of the ordered goods value.
            BigDecimal payable = purchase.getPayableAmount();
            if (purchase.getReceivingStatus() == Purchase.ReceivingStatus.PARTIAL
                    && purchase.getSubtotal() != null && purchase.getSubtotal().signum() > 0) {
                BigDecimal receivedSubtotal = purchase.getItems().stream()
                        .map(i -> i.getUnitCost() != null
                                ? i.getUnitCost().multiply(BigDecimal.valueOf(i.getReceivedQuantity()))
                                : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                payable = payable.multiply(receivedSubtotal)
                        .divide(purchase.getSubtotal(), 2, RoundingMode.HALF_UP);
            }
            BigDecimal outstanding = payable.subtract(paid);
            if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal baseOutstanding = outstanding.multiply(purchase.getExchangeRate())
                    .setScale(2, RoundingMode.HALF_UP);
            Long supplierId = purchase.getSupplier() != null ? purchase.getSupplier().getId() : null;
            String supplierName = purchase.getSupplier() != null ? purchase.getSupplier().getName() : "Walk-in";
            PayablesAgingResponse.SupplierAging row = bySupplier.computeIfAbsent(supplierId, key -> {
                PayablesAgingResponse.SupplierAging created = new PayablesAgingResponse.SupplierAging();
                created.setSupplierId(key);
                created.setSupplierName(supplierName);
                return created;
            });
            long age = ChronoUnit.DAYS.between(purchase.getPurchaseDate(), asOf);
            addToBucket(row, age, baseOutstanding);
            addToBucket(totals, age, baseOutstanding);
        }

        List<PayablesAgingResponse.SupplierAging> rows = new ArrayList<>(bySupplier.values());
        rows.sort(Comparator.comparing(row -> row.getSupplierName() != null ? row.getSupplierName() : ""));

        PayablesAgingResponse response = new PayablesAgingResponse();
        response.setAsOfDate(asOf);
        response.setSuppliers(rows);
        response.setTotals(totals);
        return response;
    }

    private void addToBucket(PayablesAgingResponse.SupplierAging row, long age, BigDecimal amount) {
        if (age <= 0) {
            row.setCurrent(row.getCurrent().add(amount));
        } else if (age <= 30) {
            row.setDays1To30(row.getDays1To30().add(amount));
        } else if (age <= 60) {
            row.setDays31To60(row.getDays31To60().add(amount));
        } else if (age <= 90) {
            row.setDays61To90(row.getDays61To90().add(amount));
        } else {
            row.setDays90Plus(row.getDays90Plus().add(amount));
        }
        row.setTotalOutstanding(row.getTotalOutstanding().add(amount));
    }

    // ---- Record payment ---------------------------------------------------

    /**
     * Records a payment against a purchase invoice. The purchase row is
     * PESSIMISTIC_WRITE locked and the amount may never exceed the outstanding
     * payable (totalAmount - returnedAmount - already paid). Cash payments
     * require an open drawer shift and post a PAY_OUT drawer movement in base
     * currency; the GL settlement is dated at the payment date.
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchasePaymentResponse recordPayment(Long purchaseId, PurchasePaymentRequest request, Long userId) {
        BigDecimal amount = request.getAmount() != null
                ? request.getAmount().setScale(2, RoundingMode.HALF_UP)
                : null;
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Payment amount must be greater than zero");
        }

        Purchase purchase = purchaseRepository.findByIdForUpdate(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + purchaseId));
        if (!purchase.getIsActive() || purchase.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Purchase not found: " + purchaseId);
        }

        if (purchase.getReceivingStatus() == Purchase.ReceivingStatus.ORDERED) {
            throw new BusinessException("Receive the purchase before recording a supplier payment.");
        }

        BigDecimal paidSoFar = paidAmount(purchaseId);
        BigDecimal outstanding = purchase.getPayableAmount().subtract(paidSoFar).max(BigDecimal.ZERO);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Purchase " + purchase.getPurchaseNumber() + " has no outstanding balance to pay");
        }
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessException("Payment amount exceeds the outstanding balance of "
                    + outstanding.setScale(2, RoundingMode.HALF_UP));
        }

        LocalDate paymentDay = request.getPaymentDate() != null ? request.getPaymentDate() : shopTimeService.today();
        if (paymentDay.isAfter(shopTimeService.today())) {
            throw new BusinessException("Payment date cannot be in the future");
        }
        if (purchase.getPurchaseDate() != null && paymentDay.isBefore(purchase.getPurchaseDate())) {
            throw new BusinessException("Payment date cannot be before the purchase date "
                    + purchase.getPurchaseDate());
        }
        LocalDateTime paymentDate = request.getPaymentDate() != null
                ? paymentDay.atStartOfDay() : shopTimeService.now();
        periodGuard.assertOpen(paymentDate);

        User recordedBy = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        PaymentMethod paymentMethod = (request.getPaymentMethod() == null
                || request.getPaymentMethod().isBlank())
                ? PaymentMethod.CASH
                : paymentValidationService.parseSettlementMethod(request.getPaymentMethod());

        CashShift employeeShift = paymentValidationService.findOpenShift(userId);
        if (paymentMethod == PaymentMethod.CASH) {
            if (employeeShift == null) {
                throw new BusinessException(
                        "CASH_PAYMENT_REQUIRES_DRAWER: Cash payments require an active cash drawer shift.");
            }
            SalePaymentRequest probe = new SalePaymentRequest();
            probe.setMethod(PaymentMethod.CASH.name());
            probe.setAmount(BigDecimal.ONE);
            PaymentValidationResponse validation = paymentValidationService.validate(
                    employeeShift, List.of(probe));
            if (!validation.isValid()) {
                throw new BusinessException(validation.getCode() + ": "
                        + "Cash payments require the cash drawer cashier or manager.");
            }
        }

        PurchasePayment payment = new PurchasePayment();
        payment.setPurchase(purchase);
        payment.setAmount(amount);
        payment.setPaymentDate(paymentDate);
        payment.setRecordedBy(recordedBy);
        payment.setNotes(request.getNotes());
        payment.setPaymentMethod(paymentMethod);
        payment.setEmployeeShift(employeeShift);
        PurchasePayment savedPayment = purchasePaymentRepository.save(payment);

        if (paymentMethod == PaymentMethod.CASH && employeeShift != null) {
            // The drawer holds base-currency cash, so the physical pay-out is
            // the invoice-currency amount converted at the purchase rate.
            BigDecimal baseAmount = amount.multiply(purchase.getExchangeRate())
                    .setScale(2, RoundingMode.HALF_UP);
            CashDrawerMovement movement = cashDrawerSessionService.recordFinancialMovement(
                    employeeShift.getDrawerSession(),
                    DrawerMovementType.PAY_OUT,
                    baseAmount.negate(),
                    "PURCHASE_PAYMENT",
                    savedPayment.getId(),
                    recordedBy,
                    employeeShift,
                    "Supplier payment for purchase " + purchase.getPurchaseNumber());
            savedPayment.setDrawerMovement(movement);
            purchasePaymentRepository.save(savedPayment);
        }

        purchase.setPaymentStatus(deriveStatus(purchase.getPayableAmount(), paidSoFar.add(amount)));
        purchaseRepository.save(purchase);

        auditLogService.logAction(userId, "PURCHASE_PAYMENT",
                "Supplier payment of " + amount + " recorded against purchase " + purchase.getPurchaseNumber(),
                "PurchasePayment", savedPayment.getId(), null, savedPayment.toString());

        return toPaymentResponse(savedPayment);
    }

    // ---- Mappers ----------------------------------------------------------

    private PurchasePaymentResponse toPaymentResponse(PurchasePayment payment) {
        PurchasePaymentResponse response = new PurchasePaymentResponse();
        response.setId(payment.getId());
        response.setPurchaseId(payment.getPurchase().getId());
        response.setPurchaseNumber(payment.getPurchase().getPurchaseNumber());
        response.setAmount(payment.getAmount());
        response.setPaymentDate(payment.getPaymentDate());
        if (payment.getRecordedBy() != null) {
            response.setRecordedById(payment.getRecordedBy().getId());
            response.setRecordedByName(payment.getRecordedBy().getFirstName() + " " + payment.getRecordedBy().getLastName());
        }
        response.setNotes(payment.getNotes());
        response.setPaymentMethod(payment.getPaymentMethod().name());
        if (payment.getEmployeeShift() != null) {
            response.setEmployeeShiftId(payment.getEmployeeShift().getId());
        }
        if (payment.getDrawerMovement() != null) {
            response.setDrawerMovementId(payment.getDrawerMovement().getId());
        }
        return response;
    }
}