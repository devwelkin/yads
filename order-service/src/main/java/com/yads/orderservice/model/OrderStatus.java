// order-service/src/main/java/com/yads/orderservice/model/OrderStatus.java
package com.yads.orderservice.model;

public enum OrderStatus {
    PENDING,
    RESERVING_STOCK,  // Intermediate state during async stock reservation saga
    PREPARING,
    ON_THE_WAY,
    DELIVERED,
    CANCELLED
}