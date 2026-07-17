package com.venkat.ecommerce.api.service;

import com.venkat.ecommerce.api.dto.CreateOrderRequest;
import com.venkat.ecommerce.api.dto.OrderItemRequest;
import com.venkat.ecommerce.api.dto.OrderResponse;
import com.venkat.ecommerce.api.entity.Customer;
import com.venkat.ecommerce.api.entity.Order;
import com.venkat.ecommerce.api.entity.OrderItem;
import com.venkat.ecommerce.api.entity.OrderStatus;
import com.venkat.ecommerce.api.entity.Payment;
import com.venkat.ecommerce.api.entity.PaymentMethod;
import com.venkat.ecommerce.api.entity.PaymentStatus;
import com.venkat.ecommerce.api.entity.Product;
import com.venkat.ecommerce.api.exception.BusinessRuleException;
import com.venkat.ecommerce.api.exception.ResourceNotFoundException;
import com.venkat.ecommerce.api.repository.CustomerRepository;
import com.venkat.ecommerce.api.repository.OrderRepository;
import com.venkat.ecommerce.api.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private OrderService orderService;

    private Customer customer() {
        return Customer.builder().id(1L).firstName("John").lastName("Doe").build();
    }

    private Product product(int stock) {
        return Product.builder()
                .id(1L)
                .name("Wireless Mouse")
                .price(new BigDecimal("24.99"))
                .stockQuantity(stock)
                .active(true)
                .build();
    }

    private CreateOrderRequest orderRequest(int quantity) {
        return CreateOrderRequest.builder()
                .customerId(1L)
                .shippingAddress("123 Maple Street")
                .paymentMethod(PaymentMethod.UPI)
                .items(List.of(OrderItemRequest.builder().productId(1L).quantity(quantity).build()))
                .build();
    }

    private Order existingOrder(OrderStatus status, PaymentStatus paymentStatus, int quantity, int currentStock) {
        Product product = product(currentStock);
        Order order = Order.builder()
                .id(1L)
                .customer(customer())
                .status(status)
                .totalAmount(new BigDecimal("124.95"))
                .shippingAddress("123 Maple Street")
                .build();
        OrderItem item = OrderItem.builder()
                .id(1L)
                .product(product)
                .quantity(quantity)
                .unitPrice(product.getPrice())
                .subtotal(product.getPrice().multiply(BigDecimal.valueOf(quantity)))
                .build();
        order.addItem(item);
        order.setPayment(Payment.builder()
                .id(1L)
                .paymentMethod(PaymentMethod.UPI)
                .paymentStatus(paymentStatus)
                .amount(order.getTotalAmount())
                .build());
        return order;
    }

    @Test
    void should_reduceStockAndCreatePendingPayment_when_orderIsPlaced() {
        // Arrange
        Product product = product(150);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer()));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        OrderResponse response = orderService.create(orderRequest(5));

        // Assert
        assertThat(product.getStockQuantity()).isEqualTo(145);
        assertThat(response.getTotalAmount()).isEqualByComparingTo("124.95");
        assertThat(response.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void should_throwResourceNotFound_when_customerDoesNotExistOnPlaceOrder() {
        // Arrange
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> orderService.create(orderRequest(5)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void should_throwBusinessRule_when_stockIsInsufficientOnPlaceOrder() {
        // Arrange
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer()));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(2)));

        // Act & Assert
        assertThatThrownBy(() -> orderService.create(orderRequest(5)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient stock");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void should_restoreStockAndRefund_when_cancellingOrderWithCompletedPayment() {
        // Arrange
        Order order = existingOrder(OrderStatus.CONFIRMED, PaymentStatus.COMPLETED, 5, 145);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        OrderResponse response = orderService.cancel(1L);

        // Assert
        assertThat(order.getItems().get(0).getProduct().getStockQuantity()).isEqualTo(150);
        assertThat(response.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(response.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void should_throwBusinessRule_when_cancellingDeliveredOrder() {
        // Arrange
        Order order = existingOrder(OrderStatus.DELIVERED, PaymentStatus.COMPLETED, 5, 145);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        // Act & Assert
        assertThatThrownBy(() -> orderService.cancel(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cannot cancel");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void should_throwBusinessRule_when_cancellingAlreadyCancelledOrder() {
        // Arrange
        Order order = existingOrder(OrderStatus.CANCELLED, PaymentStatus.PENDING, 5, 150);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        // Act & Assert
        assertThatThrownBy(() -> orderService.cancel(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cannot cancel");
        verify(orderRepository, never()).save(any());
    }
}
