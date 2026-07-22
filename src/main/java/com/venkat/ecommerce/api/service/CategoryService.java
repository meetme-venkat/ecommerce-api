package com.venkat.ecommerce.api.service;

import com.venkat.ecommerce.api.dto.CategoryRequest;
import com.venkat.ecommerce.api.dto.CategoryResponse;
import com.venkat.ecommerce.api.entity.Category;
import com.venkat.ecommerce.api.exception.DuplicateResourceException;
import com.venkat.ecommerce.api.exception.ResourceNotFoundException;
import com.venkat.ecommerce.api.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> findAll() {
        return categoryRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse findById(Long id) {
        return toResponse(getCategory(id));
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        log.info("Creating category with name={}", request.getName());
        if (categoryRepository.findByName(request.getName()).isPresent()) {
            throw new DuplicateResourceException("Category", "name", request.getName());
        }
        if (categoryRepository.findBySlug(request.getSlug()).isPresent()) {
            throw new DuplicateResourceException("Category", "slug", request.getSlug());
        }
        LocalDateTime now = LocalDateTime.now();
        Category category = Category.builder()
                .name(request.getName())
                .slug(request.getSlug())
                .description(request.getDescription())
                .createdAt(now)
                .updatedAt(now)
                .build();
        Category saved = categoryRepository.save(category);
        log.info("Created category id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        log.info("Updating category id={}", id);
        Category category = getCategory(id);
        categoryRepository.findByName(request.getName())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new DuplicateResourceException("Category", "name", request.getName());
                });
        categoryRepository.findBySlug(request.getSlug())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new DuplicateResourceException("Category", "slug", request.getSlug());
                });
        category.setName(request.getName());
        category.setSlug(request.getSlug());
        category.setDescription(request.getDescription());
        category.setUpdatedAt(LocalDateTime.now());
        return toResponse(categoryRepository.save(category));
    }

    @Transactional
    public void delete(Long id) {
        log.info("Deleting category id={}", id);
        Category category = getCategory(id);
        categoryRepository.delete(category);
    }

    private Category getCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
    }

    private CategoryResponse toResponse(Category category) {
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .slug(category.getSlug())
                .description(category.getDescription())
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .build();
    }
}
