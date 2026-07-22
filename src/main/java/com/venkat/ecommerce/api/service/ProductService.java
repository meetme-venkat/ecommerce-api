package com.venkat.ecommerce.api.service;

import com.venkat.ecommerce.api.dto.ProductRequest;
import com.venkat.ecommerce.api.dto.ProductResponse;
import com.venkat.ecommerce.api.entity.Category;
import com.venkat.ecommerce.api.entity.Product;
import com.venkat.ecommerce.api.exception.DuplicateResourceException;
import com.venkat.ecommerce.api.exception.ResourceNotFoundException;
import com.venkat.ecommerce.api.repository.CategoryRepository;
import com.venkat.ecommerce.api.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<ProductResponse> findAll() {
        return productRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return toResponse(getProduct(id));
    }

    @Transactional(readOnly = true)
    public long count() {
        return productRepository.count();
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        log.info("Creating product with sku={}", request.getSku());
        if (productRepository.findBySku(request.getSku()).isPresent()) {
            throw new DuplicateResourceException("Product", "sku", request.getSku());
        }
        Category category = getCategory(request.getCategoryId());
        LocalDateTime now = LocalDateTime.now();
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .sku(request.getSku())
                .stockQuantity(request.getStockQuantity())
                .active(request.getActive() == null ? Boolean.TRUE : request.getActive())
                .category(category)
                .createdAt(now)
                .updatedAt(now)
                .build();
        Product saved = productRepository.save(product);
        log.info("Created product id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        log.info("Updating product id={}", id);
        Product product = getProduct(id);
        productRepository.findBySku(request.getSku())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new DuplicateResourceException("Product", "sku", request.getSku());
                });
        Category category = getCategory(request.getCategoryId());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setSku(request.getSku());
        product.setStockQuantity(request.getStockQuantity());
        if (request.getActive() != null) {
            product.setActive(request.getActive());
        }
        product.setCategory(category);
        product.setUpdatedAt(LocalDateTime.now());
        return toResponse(productRepository.save(product));
    }

    @Transactional
    public void delete(Long id) {
        log.info("Deleting product id={}", id);
        Product product = getProduct(id);
        productRepository.delete(product);
    }

    private Product getProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    private Category getCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
    }

    private ProductResponse toResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .sku(product.getSku())
                .stockQuantity(product.getStockQuantity())
                .active(product.getActive())
                .categoryId(product.getCategory().getId())
                .categoryName(product.getCategory().getName())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
