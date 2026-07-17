package com.venkat.ecommerce.api.service;

import com.venkat.ecommerce.api.dto.CreateOrderRequest;
import com.venkat.ecommerce.api.dto.OrderItemRequest;
import com.venkat.ecommerce.api.dto.OrderItemResponse;
import com.venkat.ecommerce.api.dto.OrderResponse;
import com.venkat.ecommerce.api.dto.PaymentResponse;
import com.venkat.ecommerce.api.entity.Customer;
import com.venkat.ecommerce.api.entity.Order;
import com.venkat.ecommerce.api.entity.OrderItem;
import com.venkat.ecommerce.api.entity.OrderStatus;
import com.venkat.ecommerce.api.entity.Payment;
import com.venkat.ecommerce.api.entity.PaymentStatus;
import com.venkat.ecommerce.api.entity.Product;
import com.venkat.ecommerce.api.exception.BusinessRuleException;
import com.venkat.ecommerce.api.exception.ResourceNotFoundException;
import com.venkat.ecommerce.api.repository.CustomerRepository;
import com.venkat.ecommerce.api.repository.OrderRepository;
import com.venkat.ecommerce.api.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;

    /**
     * Allowed one-way order status transitions. Statuses mapped to an empty set
     * (DELIVERED, CANCELLED, REFUNDED) are final.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            OrderStatus.PENDING, EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
            OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED),
            OrderStatus.PROCESSING, EnumSet.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED),
            OrderStatus.SHIPPED, EnumSet.of(OrderStatus.DELIVERED),
            OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.REFUNDED, EnumSet.noneOf(OrderStatus.class)
    );

    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return orderRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return toResponse(getOrder(id));
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", request.getCustomerId()));

        LocalDateTime now = LocalDateTime.now();
        Order order = Order.builder()
                .customer(customer)
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .shippingAddress(request.getShippingAddress())
                .notes(request.getNotes())
                .createdAt(now)
                .updatedAt(now)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", itemRequest.getProductId()));

            if (Boolean.FALSE.equals(product.getActive())) {
                throw new BusinessRuleException("Product is not available for purchase: " + product.getName());
            }
            if (product.getStockQuantity() < itemRequest.getQuantity()) {
                throw new BusinessRuleException(
                        "Insufficient stock for product '" + product.getName() + "': requested "
                                + itemRequest.getQuantity() + ", available " + product.getStockQuantity());
            }

            // Business rule: stock is reduced when the order is placed, not at shipment.
            product.setStockQuantity(product.getStockQuantity() - itemRequest.getQuantity());

            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity()));
            OrderItem item = OrderItem.builder()
                    .product(product)
                    .quantity(itemRequest.getQuantity())
                    .unitPrice(product.getPrice())
                    .subtotal(subtotal)
                    .build();
            order.addItem(item);
            total = total.add(subtotal);
        }

        order.setTotalAmount(total);

        Payment payment = Payment.builder()
                .paymentMethod(request.getPaymentMethod())
                .paymentStatus(PaymentStatus.PENDING)
                .amount(total)
                .createdAt(now)
                .build();
        order.setPayment(payment);

        return toResponse(orderRepository.save(order));
    }

    @Transactional
    public OrderResponse updateStatus(Long id, OrderStatus target) {
        Order order = getOrder(id);
        OrderStatus current = order.getStatus();

        if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
            throw new BusinessRuleException(
                    "Invalid status transition from " + current + " to " + target);
        }

        // Cancellation carries side effects (stock restore + refund); route through cancel().
        if (target == OrderStatus.CANCELLED) {
            return cancel(id);
        }

        order.setStatus(target);
        order.setUpdatedAt(LocalDateTime.now());
        return toResponse(orderRepository.save(order));
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = getOrder(id);
        OrderStatus current = order.getStatus();

        if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(OrderStatus.CANCELLED)) {
            throw new BusinessRuleException("Cannot cancel an order in status " + current);
        }

        // Business rule: cancelling restores stock for all items.
        for (OrderItem item : order.getItems()) {
            Product product = item.getProduct();
            product.setStockQuantity(product.getStockQuantity() + item.getQuantity());
        }

        // Business rule: if payment was completed, mark it REFUNDED.
        Payment payment = order.getPayment();
        if (payment != null && payment.getPaymentStatus() == PaymentStatus.COMPLETED) {
            payment.setPaymentStatus(PaymentStatus.REFUNDED);
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());
        return toResponse(orderRepository.save(order));
    }

    private Order getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(item -> OrderItemResponse.builder()
                        .id(item.getId())
                        .productId(item.getProduct().getId())
                        .productName(item.getProduct().getName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .subtotal(item.getSubtotal())
                        .build())
                .toList();

        Payment payment = order.getPayment();
        PaymentResponse paymentResponse = payment == null ? null : PaymentResponse.builder()
                .id(payment.getId())
                .paymentMethod(payment.getPaymentMethod())
                .paymentStatus(payment.getPaymentStatus())
                .amount(payment.getAmount())
                .createdAt(payment.getCreatedAt())
                .build();

        Customer customer = order.getCustomer();
        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerId(customer.getId())
                .customerName(customer.getFirstName() + " " + customer.getLastName())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .shippingAddress(order.getShippingAddress())
                .notes(order.getNotes())
                .items(items)
                .payment(paymentResponse)
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
