// order-service/src/main/java/com/yads/orderservice/model/OrderItem.java
package com.yads.orderservice.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
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