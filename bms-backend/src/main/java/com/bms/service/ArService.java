package com.bms.service;

import com.bms.dto.request.ArPaymentRequest;
import com.bms.dto.response.ArOutstandingItemResponse;
import com.bms.dto.response.ArPaymentResponse;
import com.bms.entity.ArPayment;
import com.bms.entity.Sale;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.ArPaymentRepository;
import com.bms.repository.CustomerRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class ArService {

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ArPaymentRepository arPaymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogService auditLogService;

    // ---- Reads ------------------------------------------------------------

    public Page<ArOutstandingItemResponse> getOutstanding(String keyword, Pageable pageable) {
        String normalized = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        return saleRepository.findOutstandingAr(normalized, pageable).map(this::toOutstandingItem);
    }

    public Page<ArOutstandingItemResponse> getCustomerHistory(Long customerId, Pageable pageable) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found: " + customerId);
        }
        return saleRepository.findArHistoryByCustomerId(customerId, pageable).map(this::toOutstandingItem);
    }

    /**
     * Returns the payment ledger for a single invoice in chronological order
     * (oldest payment first). Used by the AR invoice detail dialog to show
     * when each partial/record payment was made and who recorded it.
     */
    public List<ArPaymentResponse> getInvoicePayments(Long invoiceId) {
        if (!saleRepository.existsById(invoiceId)) {
            throw new ResourceNotFoundException("Invoice not found: " + invoiceId);
        }
        return arPaymentRepository.findByInvoiceIdOrderByPaymentDateAsc(invoiceId)
                .stream()
                .map(this::toPaymentResponse)
                .toList();
    }

    // ---- Record payment ---------------------------------------------------

    /**
     * Records a payment against a credit invoice. The invoice row and the
     * customer row are both PESSIMISTIC_WRITE locked so concurrent payments
     * can never double-spend a balance. The payment advances
     * Sale.paymentStatus (UNPAID -> PARTIAL -> PAID) and decreases
     * Customer.currentBalance by the same amount.
     */
    @Transactional(rollbackFor = Exception.class)
    public ArPaymentResponse recordPayment(Long invoiceId, ArPaymentRequest request, Long userId) {
        BigDecimal amount = request.getAmount() != null
                ? request.getAmount().setScale(2, RoundingMode.HALF_UP)
                : null;
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Payment amount must be greater than zero");
        }

        Sale sale = saleRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));

        if (!Boolean.TRUE.equals(sale.getIsActive()) || sale.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Invoice not found: " + invoiceId);
        }
        if (Boolean.TRUE.equals(sale.getIsVoided())) {
            throw new BusinessException("error.ar.invoice.voided");
        }
        if (sale.getSaleType() != Sale.SaleType.CREDIT) {
            throw new BusinessException("error.ar.cash.invoice");
        }
        if (sale.getPaymentStatus() == Sale.PaymentStatus.PAID) {
            throw new BusinessException("error.ar.paid.invoice");
        }

        BigDecimal paidSoFar = sale.getAmountPaid() != null ? sale.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal remaining = sale.getTotalAmount().subtract(paidSoFar);
        if (amount.compareTo(remaining) > 0) {
            throw new BusinessException("error.ar.payment.exceeds", remaining);
        }

        User recordedBy = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        ArPayment payment = new ArPayment();
        payment.setInvoice(sale);
        payment.setAmount(amount);
        payment.setPaymentDate(LocalDateTime.now());
        payment.setRecordedBy(recordedBy);
        payment.setNotes(request.getNotes());
        arPaymentRepository.save(payment);

        // Advance the invoice's own accounting
        BigDecimal newPaid = paidSoFar.add(amount).setScale(2, RoundingMode.HALF_UP);
        sale.setAmountPaid(newPaid);
        sale.setPaymentStatus(newPaid.compareTo(sale.getTotalAmount()) >= 0
                ? Sale.PaymentStatus.PAID
                : Sale.PaymentStatus.PARTIAL);
        saleRepository.save(sale);

        // Reduce the customer's outstanding balance (row already not locked
        // here, so lock it explicitly to serialize with concurrent credit sales)
        if (sale.getCustomer() != null) {
            var customer = customerRepository.findByIdForUpdate(sale.getCustomer().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + sale.getCustomer().getId()));
            BigDecimal newBalance = customer.getCurrentBalance().subtract(amount)
                    .setScale(2, RoundingMode.HALF_UP);
            if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                newBalance = BigDecimal.ZERO;
            }
            customer.setCurrentBalance(newBalance);
            customerRepository.save(customer);
        }

        auditLogService.logAction(userId, "AR_PAYMENT",
                "AR payment of " + amount + " recorded against invoice " + sale.getInvoiceNumber(),
                "ArPayment", payment.getId(), null, payment.toString());

        return toPaymentResponse(payment);
    }

    // ---- Mappers ----------------------------------------------------------

    private ArOutstandingItemResponse toOutstandingItem(Sale sale) {
        ArOutstandingItemResponse response = new ArOutstandingItemResponse();
        response.setInvoiceId(sale.getId());
        response.setInvoiceNumber(sale.getInvoiceNumber());
        if (sale.getCustomer() != null) {
            response.setCustomerId(sale.getCustomer().getId());
        }
        response.setCustomerName(sale.getCustomerDisplayName());
        response.setTotalAmount(sale.getTotalAmount());
        BigDecimal paid = sale.getAmountPaid() != null ? sale.getAmountPaid() : BigDecimal.ZERO;
        response.setAmountPaid(paid);
        response.setBalanceDue(sale.getTotalAmount().subtract(paid).setScale(2, RoundingMode.HALF_UP));
        response.setDueDate(sale.getDueDate());
        response.setSaleDate(sale.getSaleDate());
        response.setPaymentStatus(sale.getPaymentStatus().name());
        return response;
    }

    private ArPaymentResponse toPaymentResponse(ArPayment payment) {
        ArPaymentResponse response = new ArPaymentResponse();
        response.setId(payment.getId());
        response.setInvoiceId(payment.getInvoice().getId());
        response.setInvoiceNumber(payment.getInvoice().getInvoiceNumber());
        response.setAmount(payment.getAmount());
        response.setPaymentDate(payment.getPaymentDate());
        if (payment.getRecordedBy() != null) {
            response.setRecordedById(payment.getRecordedBy().getId());
            response.setRecordedByName(payment.getRecordedBy().getFirstName() + " " + payment.getRecordedBy().getLastName());
        }
        response.setNotes(payment.getNotes());
        return response;
    }
}