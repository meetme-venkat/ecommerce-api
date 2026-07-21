package com.venkat.ecommerce.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.ecommerce.api.dto.CreateReviewRequest;
import com.venkat.ecommerce.api.dto.ReviewResponse;
import com.venkat.ecommerce.api.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReviewController.class)
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ReviewService reviewService;

    private String body(Integer rating) throws Exception {
        return objectMapper.writeValueAsString(
                CreateReviewRequest.builder()
                        .customerId(1L)
                        .rating(rating)
                        .comment("Solid")
                        .build());
    }

    @Test
    void should_return400_when_ratingAboveMax() throws Exception {
        // Arrange
        String payload = body(6);

        // Act & Assert
        mockMvc.perform(post("/api/v1/products/1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
        verify(reviewService, never()).create(any(), any());
    }

    @Test
    void should_return400_when_ratingBelowMin() throws Exception {
        // Arrange
        String payload = body(0);

        // Act & Assert
        mockMvc.perform(post("/api/v1/products/1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
        verify(reviewService, never()).create(any(), any());
    }

    @Test
    void should_return400_when_ratingMissing() throws Exception {
        // Arrange
        String payload = body(null);

        // Act & Assert
        mockMvc.perform(post("/api/v1/products/1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
        verify(reviewService, never()).create(any(), any());
    }

    @Test
    void should_return201_when_ratingWithinRange() throws Exception {
        // Arrange
        String payload = body(4);
        ReviewResponse created = ReviewResponse.builder()
                .id(1L)
                .productId(1L)
                .customerId(1L)
                .customerName("John Doe")
                .rating(4)
                .comment("Solid")
                .createdAt(LocalDateTime.now())
                .build();
        when(reviewService.create(eq(1L), any(CreateReviewRequest.class))).thenReturn(created);

        // Act & Assert
        mockMvc.perform(post("/api/v1/products/1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());
    }
}
