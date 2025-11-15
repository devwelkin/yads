// order-service/src/main/java/com/yads/orderservice/mapper/OrderMapper.java
package com.yads.orderservice.mapper;

import com.yads.orderservice.dto.OrderItemResponse;
import com.yads.orderservice.dto.OrderResponse;
import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface OrderMapper {

    // Order -> OrderResponse
    @Mapping(source = "items", target = "items")
    @Mapping(source = "pickupAddress", target = "pickupAddress")
    OrderResponse toOrderResponse(Order order);

    // OrderItem -> OrderItemResponse
    // (@Mapping not needed here since source and target field names are the same)
    OrderItemResponse toOrderItemResponse(OrderItem orderItem);

    // Note: We're not implementing request -> entity mapper
    // Because it needs external data (product name, price) from WebClient
    // This logic will be handled in the service layer
}