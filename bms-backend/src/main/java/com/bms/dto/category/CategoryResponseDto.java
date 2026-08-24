package com.bms.dto.category;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryResponseDto {
    
    private Long id;
    private String name;
    private String description;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private long productCount;
    private long lowStockCount;
    private long outOfStockCount;
    private BigDecimal totalStockValue;
    private BigDecimal totalStockQuantity;
    private long unitsSold;
    private BigDecimal revenue;
}
