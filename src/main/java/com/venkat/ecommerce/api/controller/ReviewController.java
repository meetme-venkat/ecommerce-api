package com.venkat.ecommerce.api.controller;

import com.venkat.ecommerce.api.dto.CreateReviewRequest;
import com.venkat.ecommerce.api.dto.ReviewResponse;
import com.venkat.ecommerce.api.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products/{productId}/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public List<ReviewResponse> findByProduct(@PathVariable Long productId) {
        return reviewService.findByProduct(productId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewResponse create(@PathVariable Long productId,
                                 @Valid @RequestBody CreateReviewRequest request) {
        return reviewService.create(productId, request);
    }
}
