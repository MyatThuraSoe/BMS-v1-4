package com.bms.service;

import com.bms.dto.request.SaleReturnRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.dto.response.PaymentValidationResponse;
import com.bms.dto.response.ReturnableItemsResponse;
import com.bms.dto.response.SaleReturnResponse;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SalePayment;
import com.bms.entity.SaleReturn;
import com.bms.entity.SaleReturnItem;
import com.bms.entity.StockMovement;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CustomerRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(rollbackFor = Exception.class)
public class SaleReturnService {

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private SaleItemRepository saleItemRepository;

    @Autowired
    private SaleReturnRepository saleReturnRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private PaymentValidationService paymentValidationService;

    @Autowired
    private CashDrawerSessionService cashDrawerSessionService;

    @Autowired
    private SalePaymentRepository salePaymentRepository;

    /**
     * Snapshot of what can still be returned for a sale. Prices come straight
     * off the original SaleItem so the client never computes refund money.
     */
    @Transactional(readOnly = true)
    public ReturnableItemsResponse getReturnableItems(Long saleId) {
        Sale sale = loadActiveSale(saleId);

        ReturnableItemsResponse response = new ReturnableItemsResponse();
        response.setSaleId(sale.getId());
        response.setInvoiceNumber(sale.getInvoiceNumber());
        response.setSaleDate(sale.getSaleDate());
        response.setVoided(sale.getIsVoided());
        response.setItems(sale.getItems().stream()
                .map(item -> convertToReturnableItem(sale, item))
                .collect(Collectors.toList()));
        return response;
    }

    private ReturnableItemsResponse.ReturnableItem convertToReturnableItem(Sale sale, SaleItem item) {
        ReturnableItemsResponse.ReturnableItem dto = new ReturnableItemsResponse.ReturnableItem();
        dto.setSaleItemId(item.getId());
        dto.setProductId(item.getProduct().getId());
        dto.setProductName(item.getProduct().getName());
        dto.setQuantitySold(item.getQuantity());
        int alreadyReturned = item.getQuantityRefunded() != null ? item.getQuantityRefunded() : 0;
        dto.setQuantityAlreadyReturned(alreadyReturned);
        dto.setQuantityReturnable(Math.max(0, item.getQuantity() - alreadyReturned));
        dto.setUnitPrice(item.getUnitPrice());
        dto.setUnitRefundAmount(calculateUnitRefundAmount(sale, item));
        return dto;
    }

    /**
     * Processes a customer return against an existing sale. Supports partial
     * and repeated returns; every line is priced from the ORIGINAL sale prices,
     * stock is restored, and the sale's returnStatus is recomputed from the
     * total returned quantities across all return transactions.
     */
    public SaleReturnResponse createSaleReturn(Long saleId, SaleReturnRequest request, Long userId) {
        Sale sale = loadActiveSale(saleId);

        if (sale.getIsVoided() != null && sale.getIsVoided()) {
            throw new BusinessException("Cannot return a voided sale");
        }

        User returnedBy = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        // Was every cent of this invoice already paid before this return?
        // Unpaid credit returns only reduce the debt and never move cash.
        boolean cashSettled = sale.getSaleType() == Sale.SaleType.CREDIT
                && sale.getPaymentStatus() == Sale.PaymentStatus.PAID;

        CashShift employeeShift = paymentValidationService.findOpenShift(userId);
        PaymentMethod refundMethod = resolveRefundMethod(request.getRefundMethod(), sale);
        if (sale.getSaleType() == Sale.SaleType.CREDIT && !cashSettled) {
            refundMethod = PaymentMethod.CREDIT;
        }
        if (refundMethod == PaymentMethod.CASH) {
            if (employeeShift == null) {
                throw new BusinessException(
                        "CASH_REFUND_REQUIRES_DRAWER: Cash refunds require an active cash drawer shift.");
            }
            SalePaymentRequest probe = new SalePaymentRequest();
            probe.setMethod(PaymentMethod.CASH.name());
            probe.setAmount(BigDecimal.ONE);
            PaymentValidationResponse validation = paymentValidationService.validate(
                    employeeShift, List.of(probe));
            if (!validation.isValid()) {
                throw new BusinessException(validation.getCode() + ": "
                        + "Cash refund requires the cash drawer cashier or manager.");
            }
        }

        SaleReturn saleReturn = new SaleReturn();
        saleReturn.setSale(sale);
        saleReturn.setReturnedBy(returnedBy);
        saleReturn.setReturnDate(LocalDateTime.now());
        saleReturn.setReason(request.getReason());
        saleReturn.setRefundMethod(refundMethod);
        saleReturn.setEmployeeShift(employeeShift);
        saleReturn.setExternalReference(request.getExternalReference());

        BigDecimal totalReturnAmount = BigDecimal.ZERO;
        Set<Long> requestedSaleItemIds = new HashSet<>();

        for (SaleReturnRequest.SaleReturnItemRequest itemRequest : request.getItems()) {
            if (!requestedSaleItemIds.add(itemRequest.getSaleItemId())) {
                throw new BusinessException("Duplicate return item: " + itemRequest.getSaleItemId());
            }

            // Lock this specific sale item row before reading its returned quantity —
            // prevents two concurrent return requests from both reading "0 already returned"
            // and both approving a return that, combined, exceeds what was actually sold.
            SaleItem saleItem = saleItemRepository.findByIdForUpdate(itemRequest.getSaleItemId())
                    .orElseThrow(() -> new BusinessException("Sale item not found: " + itemRequest.getSaleItemId()));

            if (!saleItem.getSale().getId().equals(saleId)) {
                throw new BusinessException("Sale item does not belong to this sale: " + itemRequest.getSaleItemId());
            }

            int alreadyReturned = saleItem.getQuantityRefunded() != null ? saleItem.getQuantityRefunded() : 0;
            int returnableQuantity = saleItem.getQuantity() - alreadyReturned;
            int requestedQuantity = itemRequest.getQuantity();
            if (requestedQuantity > returnableQuantity) {
                throw new BusinessException("Cannot return " + requestedQuantity + " of " + saleItem.getProduct().getName()
                        + ". Returnable quantity is " + returnableQuantity);
            }

            // Return the tax-inclusive original sale price (tax was charged at sale time,
            // so it must be given back too — otherwise returns silently leak tax). The
            // customer's share of any sale-level discount is deducted first, otherwise a
            // return on a discounted sale refunds more than was actually paid.
            BigDecimal unitRefundAmount = calculateUnitRefundAmount(sale, saleItem);
            BigDecimal returnAmount = unitRefundAmount
                    .multiply(BigDecimal.valueOf(requestedQuantity))
                    .setScale(2, java.math.RoundingMode.HALF_UP);

            Product product = productRepository.findByIdForUpdate(saleItem.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + saleItem.getProduct().getId()));
            product.setStockQuantity(product.getStockQuantity() + requestedQuantity);
            productRepository.save(product);

            saleItem.setQuantityRefunded(alreadyReturned + requestedQuantity);

            SaleReturnItem returnItem = new SaleReturnItem();
            returnItem.setSaleReturn(saleReturn);
            returnItem.setSaleItem(saleItem);
            returnItem.setQuantityReturned(requestedQuantity);
            returnItem.setReturnAmount(returnAmount);
            saleReturn.getItems().add(returnItem);

            StockMovement movement = new StockMovement();
            movement.setProduct(product);
            movement.setMovementType(StockMovement.MovementType.ADJUSTMENT_IN);
            movement.setQuantity(requestedQuantity);
            movement.setReferenceType(StockMovement.ReferenceType.RETURN);
            movement.setReferenceId(sale.getId());
            movement.setDescription("Stock restored from sale return: " + sale.getInvoiceNumber());
            movement.setCreatedBy(returnedBy);
            movement.setMovementDate(LocalDateTime.now());
            stockMovementRepository.save(movement);

            totalReturnAmount = totalReturnAmount.add(returnAmount);
        }

        saleReturn.setTotalReturnAmount(totalReturnAmount);
        SaleReturn savedReturn = saleReturnRepository.save(saleReturn);

        // Cash physically leaves the drawer only for a real cash refund. An
        // unpaid credit return is a balance reduction, not cash.
        if (refundMethod == PaymentMethod.CASH && totalReturnAmount.compareTo(BigDecimal.ZERO) > 0) {
            CashDrawerSession session = employeeShift != null ? employeeShift.getDrawerSession() : null;
            CashDrawerMovement movement = cashDrawerSessionService.recordFinancialMovement(
                    session,
                    DrawerMovementType.CASH_REFUND,
                    totalReturnAmount.negate(),
                    "SALE_RETURN",
                    savedReturn.getId(),
                    returnedBy,
                    employeeShift,
                    "Cash refund for " + sale.getInvoiceNumber()
                            + (request.getExternalReference() != null
                            ? " | Ref: " + request.getExternalReference() : ""));
            savedReturn.setDrawerMovement(movement);
            savedReturn = saleReturnRepository.save(savedReturn);
        }

        updateSaleReturnStatus(sale);

        // Was every cent of this invoice already paid in cash before this return?
        // If so the customer balance was already reduced by those payments and a
        // return only entitles the customer to a cash refund — never a second
        // balance reduction.
        // (cashSettled is resolved above, before the refund tender is chosen.)

        // Credit-invoice returns must touch the invoice itself, not just the
        // customer's balance: accumulate the returned amount so the invoice's
        // outstanding balance stays (total - paid - returned). Otherwise the
        // customer balance drops while the AR invoice still shows the full
        // debt forever — a permanent phantom balance that can never be cleared.
        if (sale.getSaleType() == Sale.SaleType.CREDIT) {
            BigDecimal paid = sale.getAmountPaid() != null ? sale.getAmountPaid() : BigDecimal.ZERO;
            BigDecimal returnedSoFar = sale.getAmountReturned();
            sale.setAmountReturned(returnedSoFar.add(totalReturnAmount).setScale(2, java.math.RoundingMode.HALF_UP));

            // Recompute settlement: once payments + returns fully cover the invoice
            // it reaches PAID, so a paid-down invoice never stays stuck on UNPAID.
            if (sale.getAmountReturned().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal settled = sale.getAmountReturned().add(paid);
                if (settled.compareTo(sale.getTotalAmount()) >= 0) {
                    sale.setPaymentStatus(Sale.PaymentStatus.PAID);
                } else if (paid.compareTo(BigDecimal.ZERO) > 0) {
                    sale.setPaymentStatus(Sale.PaymentStatus.PARTIAL);
                } else {
                    sale.setPaymentStatus(Sale.PaymentStatus.UNPAID);
                }
            }
        }
        saleRepository.save(sale);

        // Returns on credit invoices that were UNPAID/PARTIAL should NOT
        // dispense cash — they reduce what the customer owes instead.
        if (sale.getSaleType() == Sale.SaleType.CREDIT && !cashSettled) {
            reverseCreditBalance(sale, totalReturnAmount);
        }

        auditLogService.logAction(userId, "SALE_RETURN",
                "Sale return processed for sale: " + sale.getInvoiceNumber() + ". Amount: " + totalReturnAmount,
                "SaleReturn", savedReturn.getId(), null, savedReturn.toString());

        return convertToResponse(savedReturn);
    }

    /**
     * Prefers the caller's explicit refund method; otherwise mirrors the
     * original sale when it had a single method. Older sales have no payment
     * rows and stay on the historical cash-refund default.
     */
    private PaymentMethod resolveRefundMethod(String raw, Sale sale) {
        if (raw != null && !raw.isBlank()) {
            return paymentValidationService.parseMethod(raw);
        }
        List<SalePayment> payments = salePaymentRepository
                .findBySaleIdOrderByPaidAtAsc(sale.getId());
        if (payments.isEmpty()) {
            return PaymentMethod.CASH;
        }
        Set<PaymentMethod> methods = payments.stream()
                .map(SalePayment::getPaymentMethod)
                .collect(Collectors.toSet());
        if (methods.size() == 1) {
            return methods.iterator().next();
        }
        return methods.contains(PaymentMethod.CASH)
                ? PaymentMethod.CASH : methods.iterator().next();
    }

    /**
     * Recomputes COMPLETED / PARTIALLY_RETURNED / FULLY_RETURNED from the total
     * quantity returned across ALL return transactions on this sale.
     */
    private void updateSaleReturnStatus(Sale sale) {
        long totalSold = 0;
        long totalReturned = 0;
        for (SaleItem item : sale.getItems()) {
            totalSold += item.getQuantity() != null ? item.getQuantity() : 0;
            totalReturned += item.getQuantityRefunded() != null ? item.getQuantityRefunded() : 0;
        }
        if (totalSold > 0 && totalReturned >= totalSold) {
            sale.setReturnStatus(Sale.ReturnStatus.FULLY_RETURNED);
        } else if (totalReturned > 0) {
            sale.setReturnStatus(Sale.ReturnStatus.PARTIALLY_RETURNED);
        } else {
            sale.setReturnStatus(Sale.ReturnStatus.COMPLETED);
        }
        saleRepository.save(sale);
    }

    private void reverseCreditBalance(Sale sale, BigDecimal returnAmount) {
        if (sale.getCustomer() == null) {
            return;
        }
        com.bms.entity.Customer creditCustomer = customerRepository.findByIdForUpdate(sale.getCustomer().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + sale.getCustomer().getId()));
        BigDecimal reduced = returnAmount != null ? returnAmount : BigDecimal.ZERO;
        BigDecimal newBalance = creditCustomer.getCurrentBalance().subtract(reduced)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            newBalance = BigDecimal.ZERO;
        }
        creditCustomer.setCurrentBalance(newBalance);
        customerRepository.save(creditCustomer);
    }

    /**
     * Tax-inclusive per-unit refund derived ONLY from the stored sale prices,
     * less this line's allocated share of the sale-level discount.
     */
    private BigDecimal calculateUnitRefundAmount(Sale sale, SaleItem saleItem) {
        BigDecimal itemTotal = saleItem.getTotalPrice() != null ? saleItem.getTotalPrice() : BigDecimal.ZERO;
        BigDecimal itemTax = saleItem.getTaxAmount() != null ? saleItem.getTaxAmount() : BigDecimal.ZERO;
        int itemQty = saleItem.getQuantity() != null && saleItem.getQuantity() > 0 ? saleItem.getQuantity() : 1;
        BigDecimal allocatedDiscount = SaleDiscountUtils.discountForItem(sale, saleItem);
        return SaleDiscountUtils.unitRefundAmount(itemTotal, itemTax, allocatedDiscount, itemQty);
    }

    private Sale loadActiveSale(Long saleId) {
        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found: " + saleId));
        if (!sale.getIsActive() || sale.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Sale not found: " + saleId);
        }
        return sale;
    }

    @Transactional(readOnly = true)
    public SaleReturnResponse getReturnById(Long id) {
        SaleReturn saleReturn = saleReturnRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale return not found: " + id));
        return convertToResponse(saleReturn);
    }

    @Transactional(readOnly = true)
    public List<SaleReturnResponse> getReturnsForSale(Long saleId) {
        return saleReturnRepository.findBySaleIdOrderByReturnDateDesc(saleId).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<SaleReturnResponse> getAllReturns(Long saleId, String invoice, Pageable pageable) {
        Page<SaleReturn> page = saleReturnRepository.findFilteredReturns(
                saleId,
                (invoice != null && !invoice.isBlank()) ? invoice : null,
                pageable);
        return page.map(this::convertToResponse);
    }

    public SaleReturnResponse convertToResponse(SaleReturn saleReturn) {
        SaleReturnResponse response = new SaleReturnResponse();
        response.setId(saleReturn.getId());
        response.setSaleId(saleReturn.getSale().getId());
        response.setInvoiceNumber(saleReturn.getSale().getInvoiceNumber());
        response.setReturnedBy(saleReturn.getReturnedBy().getId());
        response.setReturnedByUsername(saleReturn.getReturnedBy().getUsername());
        response.setReturnDate(saleReturn.getReturnDate());
        response.setReason(saleReturn.getReason());
        response.setTotalReturnAmount(saleReturn.getTotalReturnAmount());
        response.setRefundMethod(saleReturn.getRefundMethod().name());
        if (saleReturn.getEmployeeShift() != null) {
            response.setEmployeeShiftId(saleReturn.getEmployeeShift().getId());
        }
        if (saleReturn.getDrawerMovement() != null) {
            response.setDrawerMovementId(saleReturn.getDrawerMovement().getId());
        }
        response.setItems(saleReturn.getItems().stream().map(item -> {
            SaleReturnResponse.SaleReturnItemResponse itemResponse = new SaleReturnResponse.SaleReturnItemResponse();
            itemResponse.setId(item.getId());
            itemResponse.setSaleItemId(item.getSaleItem().getId());
            itemResponse.setProductId(item.getSaleItem().getProduct().getId());
            itemResponse.setProductName(item.getSaleItem().getProduct().getName());
            itemResponse.setQuantityReturned(item.getQuantityReturned());
            itemResponse.setReturnAmount(item.getReturnAmount());
            return itemResponse;
        }).collect(Collectors.toList()));
        return response;
    }

    // Batch map a page of sales' returns so DTOs build in a single query instead of one per sale.
    public Map<Long, List<SaleReturn>> findBySaleIds(List<Long> saleIds) {
        return saleIds.isEmpty()
                ? Map.of()
                : saleReturnRepository.findBySaleIdInOrderByReturnDateDesc(saleIds).stream()
                        .collect(Collectors.groupingBy(r -> r.getSale().getId()));
    }

    @Transactional(readOnly = true)
    public List<SaleReturn> findBySaleId(Long saleId) {
        return saleReturnRepository.findBySaleIdOrderByReturnDateDesc(saleId);
    }
}
