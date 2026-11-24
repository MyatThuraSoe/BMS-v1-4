package com.bms.service;

import com.bms.dto.receipt.ArPaymentReceiptDto;
import com.bms.dto.receipt.ReceiptDto;
import com.bms.dto.receipt.ReceiptItemDto;
import com.bms.entity.ArPayment;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SaleReturn;
import com.bms.entity.User;
import com.bms.exception.ResourceNotFoundException;
import com.bms.exception.StateConflictException;
import com.bms.repository.ArPaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;



import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReceiptService {

    /** A balance of nothing is still money with two decimals on the paper. */
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2);

    @Autowired
    private ShopTimeService shopTimeService;


    private final SaleRepository saleRepository;


    private final UserRepository userRepository;

    @Autowired
    private ArPaymentRepository arPaymentRepository;

    @Autowired
    private SaleReturnRepository saleReturnRepository;

//    // ✅ ADD THIS CONSTRUCTOR to make the red lines disappear
//    public ReceiptService(SaleRepository saleRepository, UserRepository userRepository) {
//        this.saleRepository = saleRepository;
//        this.userRepository = userRepository;
//    }


    @Transactional(readOnly = true)
    public ReceiptDto getReceiptByInvoiceNumber(String invoiceNumber) {
        Sale sale = saleRepository.findByInvoiceNumber(invoiceNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found with invoice number: " + invoiceNumber));

        return toReceiptDto(refuseVoided(sale));
    }

    @Transactional(readOnly = true)
    public ReceiptDto getReceiptById(Long id) {
        Sale sale = saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found with id: " + id));

        return toReceiptDto(refuseVoided(sale));
    }

    /**
     * A voided invoice is not a sale anyone can be handed paper for, and both
     * receipts have to refuse it the same way: whoever asked gets to read which.
     */
    private static Sale refuseVoided(Sale sale) {
        if (Boolean.TRUE.equals(sale.getIsVoided())) {
            throw new StateConflictException("Invoice was voided: " + sale.getInvoiceNumber());
        }
        return sale;
    }

    private ReceiptDto toReceiptDto(Sale sale) {
        String cashierName = "Unknown";
        if (sale.getCashierId() != null) {
            User cashier = userRepository.findById(sale.getCashierId()).orElse(null);
            if (cashier != null) {
                cashierName = cashier.getUsername();
            }
        }

        String customerName = resolveCustomerName(sale);

        List<ReceiptItemDto> items = sale.getItems().stream()
                .map(this::toReceiptItemDto)
                .toList();

        ReceiptDto dto = new ReceiptDto(
                sale.getInvoiceNumber(),
                sale.getId(),
                sale.getSaleDate(),
                cashierName,
                customerName,
                items,
                sale.getSubtotal(),
                sale.getTaxAmount(),
                sale.getDiscountAmount(),
                sale.getTotalAmount(),
                sale.getAmountPaid(),
                sale.getChangeGiven(),
                sale.getPaymentMethod().name(),
                sale.getSaleType() != null ? sale.getSaleType().name() : "CASH",
                sale.getPaymentStatus() != null ? sale.getPaymentStatus().name() : "PAID",
                sale.getDueDate(),
                balanceOf(sale)
        );
        dto.setAmountReturned(amount(sale.getAmountReturned()));
        return dto;
    }

    /**
     * What is still owed on the invoice: the total less what was paid and less the
     * goods handed back, which is the same arithmetic the AR screens use. A return
     * that overshoots what is left is money the shop owes the customer, so it stops
     * at zero rather than printing a negative debt.
     */
    private static BigDecimal balanceOf(Sale sale) {
        return nonNegative(sale.getTotalAmount()
                .subtract(amount(sale.getAmountPaid()))
                .subtract(amount(sale.getAmountReturned())));
    }

    private static BigDecimal amount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        return rounded.compareTo(BigDecimal.ZERO) < 0 ? ZERO_MONEY : rounded;
    }

    private String resolveCustomerName(Sale sale) {
        if (sale.getCustomer() != null) {
            String displayName = sale.getCustomerDisplayName();
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
            return sale.getCustomer().getFirstName() + " " + sale.getCustomer().getLastName();
        }
        String displayName = sale.getCustomerDisplayName();
        if (displayName == null || displayName.isBlank()
                || "Walk-in".equalsIgnoreCase(displayName.trim())
                || "Walk-in Customer".equalsIgnoreCase(displayName.trim())) {
            return null;
        }
        return displayName;
    }

    private ReceiptItemDto toReceiptItemDto(SaleItem item) {
        return new ReceiptItemDto(
                item.getProduct().getId(),
                item.getId(),
                item.getProduct().getName(),
                item.getProduct().getSku(),
                item.getProduct().getUnit(),
                item.getQuantity(),
                item.getQuantityRefunded(),
                item.getUnitPrice(),
                item.getTotalPrice()
        );
    }

    /**
     * Builds the data for an AR payment receipt (a payment made against a
     * credit invoice). Used by the "print payment receipt" action. The balance it
     * carries is the one that stood right after this collection, so reprinting an
     * old slip does not hand the customer today's number.
     */
    @Transactional(readOnly = true)
    public ArPaymentReceiptDto getArPaymentReceipt(Long paymentId) {
        ArPayment payment = arPaymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        Sale sale = refuseVoided(payment.getInvoice());

        ArPaymentReceiptDto dto = new ArPaymentReceiptDto();
        dto.setPaymentId(payment.getId());
        dto.setInvoiceNumber(sale.getInvoiceNumber());
        dto.setCustomerName(resolveCustomerName(sale));
        dto.setAmount(payment.getAmount());
        dto.setPaymentDate(payment.getPaymentDate());
        if (payment.getRecordedBy() != null) {
            User user = payment.getRecordedBy();
            dto.setRecordedByName((user.getFirstName() + " " + user.getLastName()).trim());
            if (dto.getRecordedByName().isBlank()) {
                dto.setRecordedByName(user.getUsername());
            }
        }
        dto.setNotes(payment.getNotes());

        // What the customer owed when this receipt was first printed: today's paid
        // total less every collection recorded after this one. Reprints are asked for
        // at the counter to prove what was settled that day, not to show today's book.
        BigDecimal collectedSince = amount(arPaymentRepository
                .sumAmountByInvoiceIdAndPaymentDateAfterExcludingReversed(sale.getId(), payment.getPaymentDate()));
        BigDecimal paidAtThatTime = amount(sale.getAmountPaid()).subtract(collectedSince);

        BigDecimal returnedAtThatTime = saleReturnRepository.findBySaleIdOrderByReturnDateDesc(sale.getId())
                .stream()
                .filter(r -> r.getReturnDate() != null
                        && !r.getReturnDate().isAfter(payment.getPaymentDate()))
                .map(r -> amount(r.getTotalReturnAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        dto.setAmountReturned(returnedAtThatTime);
        // Returns are counted whole, whenever they happened: the invoice the customer
        // is holding has that much less on it, and hiding it would disagree with the AR screen.
        dto.setBalanceAfter(nonNegative(sale.getTotalAmount()
                .subtract(dto.getAmountReturned())
                .subtract(paidAtThatTime)));
        return dto;
    }
}
