/*
 * File: order-service/src/test/java/com/yads/orderservice/service/OrderServiceImplTest.java
 */
package com.yads.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yads.common.exception.AccessDeniedException;
import com.yads.common.model.Address;
import com.yads.orderservice.dto.OrderItemRequest;
import com.yads.orderservice.dto.OrderRequest;
import com.yads.orderservice.dto.OrderResponse;
import com.yads.orderservice.mapper.OrderMapper;
import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderItem;
import com.yads.orderservice.model.OrderStatus;
import com.yads.orderservice.model.OutboxEvent;
import com.yads.orderservice.model.ProductSnapshot;
import com.yads.orderservice.repository.OrderRepository;
import com.yads.orderservice.repository.OutboxRepository;
import com.yads.orderservice.repository.ProductSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductSnapshotRepository productSnapshotRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private OrderMapper orderMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper(); // Spy ensures real JSON serialization

    @InjectMocks
    private OrderServiceImpl orderService;

    private Jwt mockJwt;
    private UUID userId;
    private UUID storeId;
    private UUID productId;
    private Order pendingOrder;

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        userId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        productId = UUID.randomUUID();

        // Base Mock JWT setup
        mockJwt = mock(Jwt.class);
        when(mockJwt.getSubject()).thenReturn(userId.toString());
        lenient().when(mockJwt.getSubject()).thenReturn(userId.toString());

        // Base Order Setup
        pendingOrder = new Order();
        pendingOrder.setId(UUID.randomUUID());
        pendingOrder.setUserId(userId);
        pendingOrder.setStoreId(storeId);
        pendingOrder.setStatus(OrderStatus.PENDING);
        pendingOrder.setItems(new ArrayList<>());

        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setQuantity(2);
        item.setPrice(BigDecimal.TEN);
        pendingOrder.getItems().add(item);
    }

    // --- CREATE ORDER TESTS ---

    @Test
    void createOrder_Success() {
        // Arrange
        OrderRequest request = new OrderRequest();
        request.setStoreId(storeId);
        request.setShippingAddress(new Address());

        OrderItemRequest itemReq = new OrderItemRequest();
        itemReq.setProductId(productId);
        itemReq.setQuantity(2);
        request.setItems(List.of(itemReq));

        ProductSnapshot snapshot = new ProductSnapshot();
        snapshot.setProductId(productId);
        snapshot.setStoreId(storeId);
        snapshot.setPrice(BigDecimal.TEN);
        snapshot.setAvailable(true);
        snapshot.setName("Burger");

        when(productSnapshotRepository.findAllById(anyList())).thenReturn(List.of(snapshot));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> {
            Order o = i.getArgument(0);
            o.setId(UUID.randomUUID());
            o.setCreatedAt(Instant.now());
            return o;
        });
        when(orderMapper.toOrderResponse(any())).thenReturn(OrderResponse.builder().id(UUID.randomUUID()).build());

        // Act
        OrderResponse response = orderService.createOrder(request, mockJwt);

        // Assert
        assertThat(response).isNotNull();

        // Verify Outbox Event
        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo("order.created");
        assertThat(eventCaptor.getValue().getAggregateType()).isEqualTo("ORDER");
    }

    @Test
    void createOrder_Fails_WhenProductFromDifferentStore() {
        // Arrange
        OrderRequest request = new OrderRequest();
        request.setStoreId(storeId);
        OrderItemRequest itemReq = new OrderItemRequest();
        itemReq.setProductId(productId);
        itemReq.setQuantity(1);
        request.setItems(List.of(itemReq));

        ProductSnapshot snapshot = new ProductSnapshot();
        snapshot.setProductId(productId);
        snapshot.setStoreId(UUID.randomUUID()); // Different store
        snapshot.setPrice(BigDecimal.TEN);

        when(productSnapshotRepository.findAllById(anyList())).thenReturn(List.of(snapshot));

        // Act & Assert
        assertThatThrownBy(() -> orderService.createOrder(request, mockJwt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to store");
    }

    // --- ACCEPT ORDER TESTS (SAGA START) ---

    @Test
    void acceptOrder_Success_StoreOwner() {
        // Arrange
        setupRoles(mockJwt, List.of("STORE_OWNER"));
        when(mockJwt.getClaim("store_id")).thenReturn(storeId.toString()); // Owner owns THIS store

        when(orderRepository.findById(pendingOrder.getId())).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderMapper.toOrderResponse(any())).thenReturn(OrderResponse.builder().status(OrderStatus.RESERVING_STOCK).build());

        // Act
        orderService.acceptOrder(pendingOrder.getId(), mockJwt);

        // Assert
        assertThat(pendingOrder.getStatus()).isEqualTo(OrderStatus.RESERVING_STOCK);

        // Verify Saga Event
        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo("order.stock_reservation.requested");
    }

    @Test
    void acceptOrder_Fails_WrongStoreOwner() {
        // Arrange
        setupRoles(mockJwt, List.of("STORE_OWNER"));
        when(mockJwt.getClaim("store_id")).thenReturn(UUID.randomUUID().toString()); // Different store

        when(orderRepository.findById(pendingOrder.getId())).thenReturn(Optional.of(pendingOrder));

        // Act & Assert
        assertThatThrownBy(() -> orderService.acceptOrder(pendingOrder.getId(), mockJwt))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- CANCEL ORDER TESTS ---

    @Test
    void cancelOrder_Success_Customer_Pending() {
        // Arrange
        // User is the customer (userId matches order.userId)
        when(orderRepository.findById(pendingOrder.getId())).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        // Act
        orderService.cancelOrder(pendingOrder.getId(), mockJwt);

        // Assert
        assertThat(pendingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        // Check Outbox event logic for ghost inventory
        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(eventCaptor.capture());

        // Since it was PENDING, logic says "itemsToRestore" might be empty or processed downstream based on "oldStatus"
        // The critical part is that the payload contains oldStatus="PENDING"
        assertThat(eventCaptor.getValue().getPayload()).contains("\"oldStatus\":\"PENDING\"");
    }

    @Test
    void cancelOrder_Success_StoreOwner_Preparing_RestoresStock() {
        // Arrange
        Order preparingOrder = pendingOrder;
        preparingOrder.setStatus(OrderStatus.PREPARING);

        setupRoles(mockJwt, List.of("STORE_OWNER"));
        when(mockJwt.getClaim("store_id")).thenReturn(storeId.toString());

        when(orderRepository.findById(preparingOrder.getId())).thenReturn(Optional.of(preparingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        // Act
        orderService.cancelOrder(preparingOrder.getId(), mockJwt);

        // Assert
        assertThat(preparingOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(eventCaptor.capture());

        // Critical: oldStatus=PREPARING signals the subscriber to increment stock
        assertThat(eventCaptor.getValue().getPayload()).contains("\"oldStatus\":\"PREPARING\"");
    }

    // --- COURIER ASSIGNMENT TESTS (Internal) ---

    @Test
    void assignCourierToOrder_Success() {
        // Arrange
        UUID courierId = UUID.randomUUID();
        when(orderRepository.findById(pendingOrder.getId())).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        // Act
        orderService.assignCourierToOrder(pendingOrder.getId(), courierId);

        // Assert
        assertThat(pendingOrder.getCourierId()).isEqualTo(courierId);
        verify(outboxRepository).save(argThat(event ->
                event.getType().equals("order.assigned") &&
                        event.getAggregateType().equals("ORDER")
        ));
    }

    // Helper to mock Keycloak nested roles
    private void setupRoles(Jwt jwtMock, List<String> roles) {
        Map<String, Object> backendMap = Map.of("roles", roles);
        Map<String, Object> accessMap = Map.of("yads-backend", backendMap);
        when(jwtMock.getClaim("resource_access")).thenReturn(accessMap);
    }
}