package com.venkat.ecommerce.api.service;

import com.venkat.ecommerce.api.dto.CategoryRequest;
import com.venkat.ecommerce.api.dto.CategoryResponse;
import com.venkat.ecommerce.api.entity.Category;
import com.venkat.ecommerce.api.exception.DuplicateResourceException;
import com.venkat.ecommerce.api.exception.ResourceNotFoundException;
import com.venkat.ecommerce.api.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    void should_createCategory_when_nameAndSlugAreUnique() {
        // Arrange
        CategoryRequest request = CategoryRequest.builder()
                .name("Electronics")
                .slug("electronics")
                .description("Gadgets")
                .build();
        when(categoryRepository.findByName("Electronics")).thenReturn(Optional.empty());
        when(categoryRepository.findBySlug("electronics")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> {
            Category saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        // Act
        CategoryResponse response = categoryService.create(request);

        // Assert
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("Electronics");
        assertThat(response.getSlug()).isEqualTo("electronics");
    }

    @Test
    void should_throwDuplicateResource_when_categoryNameAlreadyExists() {
        // Arrange
        CategoryRequest request = CategoryRequest.builder()
                .name("Electronics")
                .slug("electronics")
                .build();
        when(categoryRepository.findByName("Electronics"))
                .thenReturn(Optional.of(Category.builder().id(1L).name("Electronics").build()));

        // Act & Assert
        assertThatThrownBy(() -> categoryService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("name");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void should_throwDuplicateResource_when_categorySlugAlreadyExists() {
        // Arrange
        CategoryRequest request = CategoryRequest.builder()
                .name("Electronics")
                .slug("electronics")
                .build();
        when(categoryRepository.findByName("Electronics")).thenReturn(Optional.empty());
        when(categoryRepository.findBySlug("electronics"))
                .thenReturn(Optional.of(Category.builder().id(1L).slug("electronics").build()));

        // Act & Assert
        assertThatThrownBy(() -> categoryService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("slug");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void should_throwResourceNotFound_when_categoryDoesNotExist() {
        // Arrange
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> categoryService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Category");
    }
}
