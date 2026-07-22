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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;

    @Transactional
    public ReviewResponse create(Long productId, CreateReviewRequest request) {
        log.info("Creating review for productId={} by customerId={}", productId, request.getCustomerId());
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", request.getCustomerId()));

        Review review = Review.builder()
                .product(product)
                .customer(customer)
                .rating(request.getRating())
                .comment(request.getComment())
                .createdAt(LocalDateTime.now())
                .build();

        Review saved = reviewRepository.save(review);
        log.info("Created review id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> findByProduct(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product", productId);
        }
        return reviewRepository.findByProductIdOrderByCreatedAtDesc(productId).stream()
                .map(this::toResponse)
                .toList();
    }

    private ReviewResponse toResponse(Review review) {
        Customer customer = review.getCustomer();
        return ReviewResponse.builder()
                .id(review.getId())
                .productId(review.getProduct().getId())
                .customerId(customer.getId())
                .customerName(customer.getFirstName() + " " + customer.getLastName())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(review.getCreatedAt())
                .build();
    }
}
