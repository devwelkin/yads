// order-service/src/main/java/com/yads/orderservice/repository/OrderRepository.java
package com.yads.orderservice.repository;

import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    // find all orders for a user
    List<Order> findByUserId(UUID userId);

    // find all orders for a store
    List<Order> findByStoreId(UUID storeId);

    // find orders by status (e.g. orders that are "preparing")
    List<Order> findByStatus(OrderStatus status);
}