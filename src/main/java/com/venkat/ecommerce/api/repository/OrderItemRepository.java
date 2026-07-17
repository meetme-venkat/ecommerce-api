package com.venkat.ecommerce.api.repository;

import com.venkat.ecommerce.api.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
