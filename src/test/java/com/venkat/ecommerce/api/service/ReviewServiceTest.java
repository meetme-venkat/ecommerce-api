package com.venkat.ecommerce.api.service;

import com.venkat.ecommerce.api.dto.CreateReviewRequest;
import com.venkat.ecommerce.api.dto.ReviewResponse;
import com.venkat.ecommerce.api.entity.Customer;
import com.venkat.ecommerce.api.entity.Product;
import com.venkat.ecommerce.api.entity.Review;
import com.venkat.ecommerce.api.exception.ResourceNotFoundException;
import com.venkat.ecommerce.api.repository.CustomerRepository;
import com.venkat.ecommerce.api.repository.ProductRepository;
import com.venkat.ecommerce.api.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private ReviewService reviewService;

    private Product product() {
        return Product.builder().id(1L).name("Wireless Mouse").build();
    }

    private Customer customer() {
        return Customer.builder().id(1L).firstName("John").lastName("Doe").build();
    }

    private CreateReviewRequest request() {
        return CreateReviewRequest.builder()
                .customerId(1L)
                .rating(5)
                .comment("Great product")
                .build();
    }

    @Test
    void should_createReview_when_productAndCustomerExist() {
        // Arrange
        when(productRepository.findById(1L)).thenReturn(Optional.of(product()));
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer()));
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> {
            Review saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        // Act
        ReviewResponse response = reviewService.create(1L, request());

        // Assert
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getProductId()).isEqualTo(1L);
        assertThat(response.getCustomerName()).isEqualTo("John Doe");
        assertThat(response.getRating()).isEqualTo(5);
    }

    @Test
    void should_throwResourceNotFound_when_productDoesNotExistOnCreate() {
        // Arrange
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> reviewService.create(99L, request()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product");
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void should_throwResourceNotFound_when_customerDoesNotExistOnCreate() {
        // Arrange
        when(productRepository.findById(1L)).thenReturn(Optional.of(product()));
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> reviewService.create(1L, request()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer");
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void should_returnReviewsNewestFirst_when_listingForProduct() {
        // Arrange
        Customer customer = customer();
        Product product = product();
        Review newer = Review.builder()
                .id(2L).product(product).customer(customer).rating(5)
                .comment("Newer").createdAt(LocalDateTime.now()).build();
        Review older = Review.builder()
                .id(1L).product(product).customer(customer).rating(3)
                .comment("Older").createdAt(LocalDateTime.now().minusDays(1)).build();
        when(productRepository.existsById(1L)).thenReturn(true);
        when(reviewRepository.findByProductIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(newer, older));

        // Act
        List<ReviewResponse> responses = reviewService.findByProduct(1L);

        // Assert
        assertThat(responses).extracting(ReviewResponse::getComment)
                .containsExactly("Newer", "Older");
    }

    @Test
    void should_throwResourceNotFound_when_listingReviewsForMissingProduct() {
        // Arrange
        when(productRepository.existsById(99L)).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> reviewService.findByProduct(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product");
        verify(reviewRepository, never()).findByProductIdOrderByCreatedAtDesc(any());
    }
}
