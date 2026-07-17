package com.venkat.ecommerce.api.service;

import com.venkat.ecommerce.api.dto.ProductRequest;
import com.venkat.ecommerce.api.dto.ProductResponse;
import com.venkat.ecommerce.api.entity.Category;
import com.venkat.ecommerce.api.entity.Product;
import com.venkat.ecommerce.api.exception.DuplicateResourceException;
import com.venkat.ecommerce.api.exception.ResourceNotFoundException;
import com.venkat.ecommerce.api.repository.CategoryRepository;
import com.venkat.ecommerce.api.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private ProductService productService;

    private ProductRequest sampleRequest() {
        return ProductRequest.builder()
                .name("Wireless Mouse")
                .description("Ergonomic")
                .price(new BigDecimal("24.99"))
                .sku("ELEC-MOU-001")
                .stockQuantity(150)
                .active(true)
                .categoryId(1L)
                .build();
    }

    @Test
    void should_createProduct_when_skuIsUniqueAndCategoryExists() {
        // Arrange
        ProductRequest request = sampleRequest();
        when(productRepository.findBySku("ELEC-MOU-001")).thenReturn(Optional.empty());
        when(categoryRepository.findById(1L))
                .thenReturn(Optional.of(Category.builder().id(1L).name("Electronics").build()));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        // Act
        ProductResponse response = productService.create(request);

        // Assert
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getSku()).isEqualTo("ELEC-MOU-001");
        assertThat(response.getCategoryName()).isEqualTo("Electronics");
    }

    @Test
    void should_throwDuplicateResource_when_skuAlreadyExists() {
        // Arrange
        ProductRequest request = sampleRequest();
        when(productRepository.findBySku("ELEC-MOU-001"))
                .thenReturn(Optional.of(Product.builder().id(2L).sku("ELEC-MOU-001").build()));

        // Act & Assert
        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("sku");
        verify(productRepository, never()).save(any());
    }

    @Test
    void should_throwResourceNotFound_when_categoryDoesNotExistOnCreate() {
        // Arrange
        ProductRequest request = sampleRequest();
        when(productRepository.findBySku("ELEC-MOU-001")).thenReturn(Optional.empty());
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Category");
        verify(productRepository, never()).save(any());
    }
}
