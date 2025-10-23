// order-service/src/main/java/com/yads/orderservice/repository/OrderItemRepository.java
package com.yads.orderservice.repository;

import com.yads.orderservice.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    // find all order items for a product
    List<OrderItem> findByProductId(UUID productId);
}