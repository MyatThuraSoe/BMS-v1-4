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

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public Page<CategoryResponseDto> getAllCategories(Pageable pageable) {
        return categoryRepository.findAllActive(pageable)
                .map(this::toResponseDto);
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

    private CategoryResponseDto toResponseDto(Category category) {
        return new CategoryResponseDto(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getIsActive(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }
}
