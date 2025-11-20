// order-service/src/main/java/com/yads/orderservice/model/Order.java
package com.yads.orderservice.model;

import com.yads.common.model.Address;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
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

    // Courier ID who is assigned to deliver this order (from user-service with
    // courier role)
    // Can be null initially - assigned when order is accepted by store
    @Column(name = "courier_id")
    private UUID courierId;

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

    // Pickup address (snapshot of store's address at time of order acceptance)
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "street", column = @Column(name = "pickup_street")),
            @AttributeOverride(name = "city", column = @Column(name = "pickup_city")),
            @AttributeOverride(name = "state", column = @Column(name = "pickup_state")),
            @AttributeOverride(name = "postalCode", column = @Column(name = "pickup_postal_code")),
            @AttributeOverride(name = "country", column = @Column(name = "pickup_country")),
            @AttributeOverride(name = "latitude", column = @Column(name = "pickup_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "pickup_longitude"))
    })
    private Address pickupAddress;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // Optimistic locking: prevents zombie orders and lost updates
    // When two concurrent transactions try to modify the same order,
    // the second one will fail with OptimisticLockingFailureException
    @Version
    @Column(name = "version")
    private Long version;
}