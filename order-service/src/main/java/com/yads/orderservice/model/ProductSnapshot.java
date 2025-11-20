package com.yads.orderservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "product_snapshots")
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
public class ProductSnapshot {

    @Id // Get id from store-service
    private UUID productId;

    @Column(nullable = false)
    private UUID storeId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer stock;

    @Column(nullable = false)
    private boolean isAvailable;
}