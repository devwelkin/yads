// order-service/src/main/java/com/yads/orderservice/model/OrderItem.java
package com.yads.orderservice.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "order_items")
@Data
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Which order this item belongs to
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;


    // id from store-service
    @Column(nullable = false)
    private UUID productId;

    // product name from store-service
    @Column(nullable = false)
    private String productName;


    @Column(nullable = false)
    private Integer quantity;

    // price from store-service
    @Column(nullable = false)
    private BigDecimal price;
}