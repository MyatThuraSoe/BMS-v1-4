package com.bms.service;

import com.bms.dto.response.ReorderSuggestionDto;
import com.bms.entity.Product;
import com.bms.entity.Purchase;
import com.bms.entity.PurchaseItem;
import com.bms.entity.Supplier;
import com.bms.repository.ProductRepository;
import com.bms.repository.PurchaseItemRepository;
import com.bms.repository.SaleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class InventoryService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private PurchaseItemRepository purchaseItemRepository;

    public List<ReorderSuggestionDto> getReorderSuggestions() {
        List<ReorderSuggestionDto> suggestions = new ArrayList<>();
        
        // Get all active products
        List<Product> products = productRepository.findAllActive(org.springframework.data.domain.Pageable.unpaged()).getContent();
        
        // Calculate average daily sales for last 30 days
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        List<Object[]> avgSalesData = saleRepository.findAverageDailySalesByProductId(thirtyDaysAgo);
        
        // Map product ID to average daily sales
        Map<Long, BigDecimal> avgDailySalesMap = new HashMap<>();
        for (Object[] row : avgSalesData) {
            Long productId = (Long) row[0];
            BigDecimal avgDailySales = (BigDecimal) row[1];
            avgDailySalesMap.put(productId, avgDailySales);
        }
        
        for (Product product : products) {
            ReorderSuggestionDto dto = new ReorderSuggestionDto();
            dto.setProductId(product.getId());
            dto.setProductName(product.getName());
            dto.setCurrentStock(product.getStockQuantity());
            dto.setMinStockLevel(product.getMinStockLevel());
            
            BigDecimal avgDailySales = avgDailySalesMap.getOrDefault(product.getId(), BigDecimal.ZERO);
            dto.setAverageDailySales(avgDailySales);
            
            // Calculate days until stockout
            if (avgDailySales.compareTo(BigDecimal.ZERO) > 0) {
                double daysUntilStockout = product.getStockQuantity() / avgDailySales.doubleValue();
                dto.setDaysUntilStockout(Math.round(daysUntilStockout * 10.0) / 10.0); // Round to 1 decimal
                
                // Calculate suggested reorder quantity (target 14 days of stock)
                BigDecimal targetStock = avgDailySales.multiply(BigDecimal.valueOf(14));
                int suggestedQty = targetStock.subtract(BigDecimal.valueOf(product.getStockQuantity()))
                    .max(BigDecimal.ZERO).intValue();
                dto.setSuggestedReorderQuantity(suggestedQty);
                
                // Find last supplier
                PurchaseItem lastPurchase = purchaseItemRepository.findTopByProductIdOrderByPurchaseDateDesc(product.getId());
                if (lastPurchase != null) {
                    Purchase purchase = lastPurchase.getPurchase();
                    Supplier supplier = purchase.getSupplier();
                    dto.setLastSupplierId(supplier.getId());
                    dto.setLastSupplierName(supplier.getName());
                    dto.setLastPurchaseUnitCost(lastPurchase.getUnitCost());
                }
                
                suggestions.add(dto);
            }
            // Products with no sales history are not included (as per requirements)
        }
        
        return suggestions;
    }
}
