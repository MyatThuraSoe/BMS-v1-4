package com.bms.service;

import com.bms.dto.request.SaleReturnRequest;
import com.bms.dto.response.ReturnableItemsResponse;
import com.bms.dto.response.SaleReturnResponse;
import com.bms.entity.Product;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SaleReturn;
import com.bms.entity.SaleReturnItem;
import com.bms.entity.StockMovement;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CustomerRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
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
                .map(this::convertToReturnableItem)
                .collect(Collectors.toList()));
        return response;
    }

    private ReturnableItemsResponse.ReturnableItem convertToReturnableItem(SaleItem item) {
        ReturnableItemsResponse.ReturnableItem dto = new ReturnableItemsResponse.ReturnableItem();
        dto.setSaleItemId(item.getId());
        dto.setProductId(item.getProduct().getId());
        dto.setProductName(item.getProduct().getName());
        dto.setQuantitySold(item.getQuantity());
        int alreadyReturned = item.getQuantityRefunded() != null ? item.getQuantityRefunded() : 0;
        dto.setQuantityAlreadyReturned(alreadyReturned);
        dto.setQuantityReturnable(Math.max(0, item.getQuantity() - alreadyReturned));
        dto.setUnitPrice(item.getUnitPrice());
        dto.setUnitRefundAmount(calculateUnitRefundAmount(item));
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

        SaleReturn saleReturn = new SaleReturn();
        saleReturn.setSale(sale);
        saleReturn.setReturnedBy(returnedBy);
        saleReturn.setReturnDate(LocalDateTime.now());
        saleReturn.setReason(request.getReason());

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
            // so it must be given back too — otherwise returns silently leak tax).
            BigDecimal unitRefundAmount = calculateUnitRefundAmount(saleItem);
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

        updateSaleReturnStatus(sale);

        // Returns on credit invoices that are still UNPAID/PARTIAL should NOT
        // dispense cash — they reduce what the customer owes instead.
        if (sale.getSaleType() == Sale.SaleType.CREDIT
                && sale.getPaymentStatus() != Sale.PaymentStatus.PAID) {
            reverseCreditBalance(sale, totalReturnAmount);
        }

        auditLogService.logAction(userId, "SALE_RETURN",
                "Sale return processed for sale: " + sale.getInvoiceNumber() + ". Amount: " + totalReturnAmount,
                "SaleReturn", savedReturn.getId(), null, savedReturn.toString());

        return convertToResponse(savedReturn);
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
     * Tax-inclusive per-unit refund derived ONLY from the stored sale prices.
     */
    private BigDecimal calculateUnitRefundAmount(SaleItem saleItem) {
        BigDecimal itemTotal = saleItem.getTotalPrice() != null ? saleItem.getTotalPrice() : BigDecimal.ZERO;
        BigDecimal itemTax = saleItem.getTaxAmount() != null ? saleItem.getTaxAmount() : BigDecimal.ZERO;
        int itemQty = saleItem.getQuantity() != null && saleItem.getQuantity() > 0 ? saleItem.getQuantity() : 1;
        return itemTotal.add(itemTax)
                .divide(BigDecimal.valueOf(itemQty), 4, java.math.RoundingMode.HALF_UP);
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
