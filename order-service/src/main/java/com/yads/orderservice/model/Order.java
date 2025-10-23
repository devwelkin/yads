// order-service/src/main/java/com/yads/orderservice/model/Order.java
package com.yads.orderservice.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Data
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // User ID who placed the order (from user-service)
    @Column(nullable = false)
    private UUID userId;

    // Store ID where order was placed (from store-service) 
    @Column(nullable = false)
    private UUID storeId;

    // Order items (shopping cart)
    // When order is deleted, delete all associated items (CascadeType.ALL)
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items;

    @Column(nullable = false)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING) // Store enum as string in DB (pending, delivered etc.)
    @Column(nullable = false)
    private OrderStatus status;

    // Shipping address for order delivery
    @Embedded
    private Address shippingAddress;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}