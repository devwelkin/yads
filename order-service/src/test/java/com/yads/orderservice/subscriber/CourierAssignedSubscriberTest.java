package com.yads.orderservice.subscriber;

import com.yads.common.contracts.CourierAssignedContract;
import com.yads.common.contracts.OrderAssignedContract;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourierAssignedSubscriberTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private CourierAssignedSubscriber subscriber;

    private UUID orderId;
    private UUID courierId;
    private Order order;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        courierId = UUID.randomUUID();

        order = new Order();
        order.setId(orderId);
        order.setUserId(UUID.randomUUID());
        order.setStoreId(UUID.randomUUID());
        order.setStatus(OrderStatus.PREPARING); // Happy path default
        order.setShippingAddress(new Address());
        order.setPickupAddress(new Address());
    }

    // --- Succesful Path ---

    @Test
    void handleCourierAssigned_Success() {
        // Arrange
        CourierAssignedContract contract = CourierAssignedContract.builder()
                .orderId(orderId)
                .courierId(courierId)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleCourierAssigned(contract);

        // Assert
        // 1. DB update control
        assertThat(order.getCourierId()).isEqualTo(courierId);
        verify(orderRepository).save(order);

        // 2. Notification event control
        ArgumentCaptor<OrderAssignedContract> captor = ArgumentCaptor.forClass(OrderAssignedContract.class);
        verify(rabbitTemplate).convertAndSend(
                eq("order_events_exchange"),
                eq("order.assigned"),
                captor.capture());
        assertThat(captor.getValue().getCourierId()).isEqualTo(courierId);
    }

    // --- IDEMPOTENCY / CONFLICT SCENARIOS ---

    @Test
    void handleCourierAssigned_SameCourier_Idempotent() {
        // Arrange: Order already assigned to this courier
        order.setCourierId(courierId);

        CourierAssignedContract contract = CourierAssignedContract.builder()
                .orderId(orderId)
                .courierId(courierId)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleCourierAssigned(contract);

        // Assert: Save should not be called
        verify(orderRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void handleCourierAssigned_DifferentCourier_Ignored() {
        // Arrange: Order already assigned to a different courier
        UUID otherCourier = UUID.randomUUID();
        order.setCourierId(otherCourier);

        CourierAssignedContract contract = CourierAssignedContract.builder()
                .orderId(orderId)
                .courierId(courierId) // New incoming different courier
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleCourierAssigned(contract);

        // Assert: No changes should be made
        assertThat(order.getCourierId()).isEqualTo(otherCourier); // Old one should remain
        verify(orderRepository, never()).save(any());
    }

    // --- WRONG STATUS SCENARIO ---

    @Test
    void handleCourierAssigned_WrongStatus_Ignored() {
        // Arrange
        order.setStatus(OrderStatus.PENDING); // Not yet preparing

        CourierAssignedContract contract = CourierAssignedContract.builder()
                .orderId(orderId)
                .courierId(courierId)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        subscriber.handleCourierAssigned(contract);

        // Assert
        verify(orderRepository, never()).save(any());
    }

    // --- CRITICAL: NOTIFICATION FAILURE SHOULD BE SWALLOWED ---

    @Test
    void handleCourierAssigned_NotificationFailure_StillSavesOrder() {
        // Arrange
        CourierAssignedContract contract = CourierAssignedContract.builder()
                .orderId(orderId)
                .courierId(courierId)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // RabbitMQ should throw an error
        doThrow(new RuntimeException("RabbitMQ down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Act
        subscriber.handleCourierAssigned(contract);

        // Assert
        // Exception should not be thrown (method should complete successfully)
        // DB save SHOULD happen (Because courier assignment succeeded)
        assertThat(order.getCourierId()).isEqualTo(courierId);
        verify(orderRepository).save(order);
    }

    @Test
    void handleCourierAssigned_OrderNotFound_ThrowsException() {
        // Arrange
        CourierAssignedContract contract = CourierAssignedContract.builder().orderId(orderId).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> subscriber.handleCourierAssigned(contract))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}