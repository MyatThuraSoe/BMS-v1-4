package com.bms.service;

import com.bms.dto.category.CategoryRequestDto;
import com.bms.dto.category.CategoryResponseDto;
import com.bms.entity.Category;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public Page<CategoryResponseDto> getAllCategories(Pageable pageable) {
        Map<Long, Object[]> stockStats = toMap(categoryRepository.findStockStatsByCategory());
        Map<Long, Object[]> lowStock = toMap(categoryRepository.findLowStockCountsByCategory());
        Map<Long, Object[]> outOfStock = toMap(categoryRepository.findOutOfStockCountsByCategory());
        Map<Long, Object[]> salesStats = toMap(categoryRepository.findSalesStatsByCategory());

        return categoryRepository.findAllActive(pageable)
                .map(c -> toResponseDto(c, stockStats, lowStock, outOfStock, salesStats));
    }

    @Transactional(readOnly = true)
    public List<CategoryResponseDto> getAllActiveCategories() {
        return categoryRepository.findAllActive().stream()
                .map(this::toResponseDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponseDto getCategoryById(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));
        return toResponseDto(category);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCategoryStatsSummary() {
        List<Object[]> invRows = categoryRepository.findOverallInventoryStats();
        List<Object[]> salesRows = categoryRepository.findOverallSalesStats();
        Object[] inv = invRows.isEmpty() ? null : invRows.get(0);
        Object[] sales = salesRows.isEmpty() ? null : salesRows.get(0);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCategories", categoryRepository.countActiveCategories() != null
                ? categoryRepository.countActiveCategories() : 0L);
        summary.put("totalProducts", inv != null ? ((Number) inv[0]).longValue() : 0L);
        summary.put("totalStockQuantity", inv != null ? ((Number) inv[1]).longValue() : 0L);
        summary.put("totalStockValue", inv != null && inv[2] != null ? (BigDecimal) inv[2] : BigDecimal.ZERO);
        summary.put("lowStockProducts", categoryRepository.countLowStockProducts() != null
                ? categoryRepository.countLowStockProducts() : 0L);
        summary.put("outOfStockProducts", categoryRepository.countOutOfStockProducts() != null
                ? categoryRepository.countOutOfStockProducts() : 0L);
        summary.put("unitsSold", sales != null && sales[0] != null ? ((Number) sales[0]).longValue() : 0L);
        summary.put("revenue", sales != null && sales[1] != null ? (BigDecimal) sales[1] : BigDecimal.ZERO);
        return summary;
    }

    @Transactional
    public CategoryResponseDto createCategory(CategoryRequestDto request) {
        Category category = new Category();
        category.setName(request.getName());
        category.setDescription(request.getDescription());
        category.setIsActive(true);
        
        Category saved = categoryRepository.save(category);
        return toResponseDto(saved);
    }

    @Transactional
    public CategoryResponseDto updateCategory(Long id, CategoryRequestDto request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));
        
        category.setName(request.getName());
        category.setDescription(request.getDescription());
        
        Category updated = categoryRepository.save(category);
        return toResponseDto(updated);
    }

    @Transactional
    public void deleteCategory(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));
        
        category.setIsActive(false);
        category.setDeletedAt(LocalDateTime.now());
        categoryRepository.save(category);
    }

    private Map<Long, Object[]> toMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> r));
    }

    private CategoryResponseDto toResponseDto(Category category) {
        return new CategoryResponseDto(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getIsActive(),
                category.getCreatedAt(),
                category.getUpdatedAt(),
                0L, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, 0L, BigDecimal.ZERO
        );
    }

    private CategoryResponseDto toResponseDto(Category category,
                                             Map<Long, Object[]> stockStats,
                                             Map<Long, Object[]> lowStock,
                                             Map<Long, Object[]> outOfStock,
                                             Map<Long, Object[]> salesStats) {
        Long id = category.getId();
        Object[] stock = stockStats.get(id);
        Object[] low = lowStock.get(id);
        Object[] out = outOfStock.get(id);
        Object[] sales = salesStats.get(id);

        long productCount = stock != null ? ((Number) stock[1]).longValue() : 0L;
        long totalStockQuantity = stock != null ? ((Number) stock[2]).longValue() : 0L;
        BigDecimal totalStockValue = stock != null && stock[3] != null ? (BigDecimal) stock[3] : BigDecimal.ZERO;
        long lowStockCount = low != null ? ((Number) low[1]).longValue() : 0L;
        long outOfStockCount = out != null ? ((Number) out[1]).longValue() : 0L;
        long unitsSold = sales != null && sales[1] != null ? ((Number) sales[1]).longValue() : 0L;
        BigDecimal revenue = sales != null && sales[2] != null ? (BigDecimal) sales[2] : BigDecimal.ZERO;

        return new CategoryResponseDto(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getIsActive(),
                category.getCreatedAt(),
                category.getUpdatedAt(),
                productCount, lowStockCount, outOfStockCount, totalStockValue, BigDecimal.valueOf(totalStockQuantity),
                unitsSold, revenue
        );
    }
}
