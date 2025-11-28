package com.yads.orderservice.subscriber;

import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.common.contracts.OrderCancelledContract;
import com.yads.common.contracts.StockReservationFailedContract;
import com.yads.common.contracts.StockReservedContract;
import com.yads.common.exception.ResourceNotFoundException;
import com.yads.common.model.Address;
import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderStatus;
import com.yads.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockReplySubscriberTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private StockReplySubscriber subscriber;

    private UUID orderId;
    private UUID userId;
    private UUID storeId;
    private Order order;
    private Address address;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        userId = UUID.randomUUID();
        storeId = UUID.randomUUID();

        address = new Address();
        address.setCity("Istanbul");
        address.setStreet("Bagdat Cad.");

        order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setStoreId(storeId);
        order.setShippingAddress(address); // Initial shipping address
        order.setStatus(OrderStatus.RESERVING_STOCK); // Default expected state
    }

    // --- STOK REZERVASYONU BAŞARILI (handleStockReserved) ---

    @Test
    void handleStockReserved_Success() {
        // Arrange
        Address pickupAddress = new Address();
        pickupAddress.setCity("Istanbul");
        pickupAddress.setStreet("Store Location");

        StockReservedContract contract = StockReservedContract.builder()
                .orderId(orderId)
                .storeId(storeId)
                .userId(userId)
                .pickupAddress(pickupAddress)
                .shippingAddress(address)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleStockReserved(contract);

        // Assert
        // 1. Sipariş durumu ve adresi güncellenmeli
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PREPARING);
        assertThat(order.getPickupAddress()).isEqualTo(pickupAddress);
        verify(orderRepository).save(order);

        // 2. Kurye ataması için RabbitMQ'ya mesaj gönderilmeli
        ArgumentCaptor<OrderAssignmentContract> captor = ArgumentCaptor.forClass(OrderAssignmentContract.class);
        verify(rabbitTemplate).convertAndSend(
                eq("order_events_exchange"),
                eq("order.preparing"),
                captor.capture());

        assertThat(captor.getValue().getOrderId()).isEqualTo(orderId);
        assertThat(captor.getValue().getPickupAddress()).isEqualTo(pickupAddress);
    }

    @Test
    void handleStockReserved_Idempotency_WrongStatus() {
        // Arrange
        order.setStatus(OrderStatus.CANCELLED); // Sipariş zaten iptal edilmiş
        StockReservedContract contract = StockReservedContract.builder().orderId(orderId).build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleStockReserved(contract);

        // Assert
        verify(orderRepository, never()).save(any()); // Kayıt yapılmamalı
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class)); // Mesaj atılmamalı
    }

    // --- STOK REZERVASYONU BAŞARISIZ (handleStockReservationFailed) ---

    @Test
    void handleStockReservationFailed_Success_CancelsOrder() {
        // Arrange
        String failureReason = "Insufficient Stock";
        StockReservationFailedContract contract = StockReservationFailedContract.builder()
                .orderId(orderId)
                .userId(userId)
                .reason(failureReason)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleStockReservationFailed(contract);

        // Assert
        // 1. Sipariş iptal edilmeli
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(order);

        // 2. Müşteri bildirimi için cancelled eventi fırlatılmalı
        ArgumentCaptor<OrderCancelledContract> captor = ArgumentCaptor.forClass(OrderCancelledContract.class);
        verify(rabbitTemplate).convertAndSend(
                eq("order_events_exchange"),
                eq("order.cancelled"),
                captor.capture());

        assertThat(captor.getValue().getOrderId()).isEqualTo(orderId);
        // Stok hiç ayrılmadığı için "oldStatus" RESERVING_STOCK olmalı (hayalet stok
        // oluşmaması için)
        assertThat(captor.getValue().getOldStatus()).isEqualTo("RESERVING_STOCK");
    }

    @Test
    void handleStockReservationFailed_Idempotency() {
        // Arrange
        order.setStatus(OrderStatus.CANCELLED); // Zaten iptal edilmiş
        StockReservationFailedContract contract = StockReservationFailedContract.builder().orderId(orderId).build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleStockReservationFailed(contract);

        // Assert
        verify(orderRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    // --- ORTAK HATALAR ---

    @Test
    void handleStockReserved_OrderNotFound_ThrowsException() {
        // Arrange
        StockReservedContract contract = StockReservedContract.builder().orderId(orderId).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> subscriber.handleStockReserved(contract))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void handleStockReservationFailed_OrderNotFound_ThrowsException() {
        // Arrange
        StockReservationFailedContract contract = StockReservationFailedContract.builder().orderId(orderId).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> subscriber.handleStockReservationFailed(contract))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- EDGE CASE: RabbitMQ Failure ---

    @Test
    void handleStockReserved_RabbitMQFailure_StillUpdatesOrder() {
        // Arrange
        Address pickupAddress = new Address();
        pickupAddress.setCity("Istanbul");

        StockReservedContract contract = StockReservedContract.builder()
                .orderId(orderId)
                .storeId(storeId)
                .userId(userId)
                .pickupAddress(pickupAddress)
                .shippingAddress(address)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // RabbitMQ fails but order should still be saved
        doThrow(new RuntimeException("RabbitMQ connection lost"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Act - should not throw
        subscriber.handleStockReserved(contract);

        // Assert: Order should still be updated
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PREPARING);
        assertThat(order.getPickupAddress()).isEqualTo(pickupAddress);
        verify(orderRepository).save(order);
    }

    @Test
    void handleStockReservationFailed_RabbitMQFailure_StillCancelsOrder() {
        // Arrange
        StockReservationFailedContract contract = StockReservationFailedContract.builder()
                .orderId(orderId)
                .userId(userId)
                .reason("Insufficient Stock")
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // RabbitMQ fails but order should still be cancelled
        doThrow(new RuntimeException("RabbitMQ connection lost"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Act - should not throw
        subscriber.handleStockReservationFailed(contract);

        // Assert: Order should still be cancelled
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(order);
    }

    // --- CRITICAL: Idempotency - Already PREPARING ---

    @Test
    void handleStockReserved_AlreadyPreparing_ShouldIgnore() {
        // Arrange: Order already in PREPARING state (duplicate event)
        order.setStatus(OrderStatus.PREPARING);
        order.setPickupAddress(address); // Already set

        StockReservedContract contract = StockReservedContract.builder()
                .orderId(orderId)
                .storeId(storeId)
                .userId(userId)
                .pickupAddress(address)
                .shippingAddress(address)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleStockReserved(contract);

        // Assert: Should be ignored (idempotency)
        verify(orderRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    // --- CRITICAL: Validate Pickup Address is Set ---

    @Test
    void handleStockReserved_ShouldSetPickupAddressFromContract() {
        // Arrange
        Address storePickupAddress = new Address();
        storePickupAddress.setCity("Ankara");
        storePickupAddress.setStreet("Kizilay");
        storePickupAddress.setPostalCode("06420");
        storePickupAddress.setLatitude(39.9208);
        storePickupAddress.setLongitude(32.8541);

        StockReservedContract contract = StockReservedContract.builder()
                .orderId(orderId)
                .storeId(storeId)
                .userId(userId)
                .pickupAddress(storePickupAddress)
                .shippingAddress(address)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleStockReserved(contract);

        // Assert: Pickup address should be exactly from contract
        assertThat(order.getPickupAddress()).isNotNull();
        assertThat(order.getPickupAddress().getCity()).isEqualTo("Ankara");
        assertThat(order.getPickupAddress().getStreet()).isEqualTo("Kizilay");
        assertThat(order.getPickupAddress().getPostalCode()).isEqualTo("06420");
        assertThat(order.getPickupAddress().getLatitude()).isEqualTo(39.9208);
        assertThat(order.getPickupAddress().getLongitude()).isEqualTo(32.8541);
    }

    // --- CRITICAL: Empty Items List in Cancellation ---

    @Test
    void handleStockReservationFailed_ShouldPublishEmptyItemsList() {
        // Arrange: Stock was never reserved, so no items to restore
        StockReservationFailedContract contract = StockReservationFailedContract.builder()
                .orderId(orderId)
                .userId(userId)
                .reason("Product discontinued")
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleStockReservationFailed(contract);

        // Assert: Items list should be empty (no stock restoration needed)
        ArgumentCaptor<OrderCancelledContract> captor = ArgumentCaptor.forClass(OrderCancelledContract.class);
        verify(rabbitTemplate).convertAndSend(
                eq("order_events_exchange"),
                eq("order.cancelled"),
                captor.capture());

        OrderCancelledContract cancelContract = captor.getValue();
        assertThat(cancelContract.getItems()).isEmpty();
        assertThat(cancelContract.getOldStatus()).isEqualTo("RESERVING_STOCK");
    }

    // --- CRITICAL: Shipping Address Passed to Courier Assignment ---

    @Test
    void handleStockReserved_ShouldIncludeShippingAddressInCourierAssignment() {
        // Arrange
        Address pickupAddress = new Address();
        pickupAddress.setCity("Istanbul");

        Address shippingAddress = new Address();
        shippingAddress.setCity("Izmir");
        shippingAddress.setStreet("Alsancak");

        StockReservedContract contract = StockReservedContract.builder()
                .orderId(orderId)
                .storeId(storeId)
                .userId(userId)
                .pickupAddress(pickupAddress)
                .shippingAddress(shippingAddress)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleStockReserved(contract);

        // Assert: Courier assignment contract should have both addresses
        ArgumentCaptor<OrderAssignmentContract> captor = ArgumentCaptor.forClass(OrderAssignmentContract.class);
        verify(rabbitTemplate).convertAndSend(
                eq("order_events_exchange"),
                eq("order.preparing"),
                captor.capture());

        OrderAssignmentContract assignmentContract = captor.getValue();
        assertThat(assignmentContract.getPickupAddress()).isEqualTo(pickupAddress);
        assertThat(assignmentContract.getShippingAddress()).isEqualTo(shippingAddress);
        assertThat(assignmentContract.getOrderId()).isEqualTo(orderId);
        assertThat(assignmentContract.getStoreId()).isEqualTo(storeId);
        assertThat(assignmentContract.getUserId()).isEqualTo(userId);
    }
}