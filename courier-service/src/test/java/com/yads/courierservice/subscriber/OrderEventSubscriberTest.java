package com.yads.courierservice.subscriber;

import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.common.model.Address;
import com.yads.courierservice.model.IdempotentEvent;
import com.yads.courierservice.repository.IdempotentEventRepository;
import com.yads.courierservice.service.CourierAssignmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderEventSubscriber Unit Tests")
class OrderEventSubscriberTest {

  @Mock
  private CourierAssignmentService assignmentService;

  @Mock
  private IdempotentEventRepository idempotentEventRepository;

  @InjectMocks
  private OrderEventSubscriber subscriber;

  private UUID orderId;
  private UUID storeId;
  private UUID userId;
  private OrderAssignmentContract contract;

  @BeforeEach
  void setUp() {
    orderId = UUID.randomUUID();
    storeId = UUID.randomUUID();
    userId = UUID.randomUUID();

    Address pickupAddress = new Address();
    pickupAddress.setLatitude(40.990);
    pickupAddress.setLongitude(29.020);

    contract = OrderAssignmentContract.builder()
        .orderId(orderId)
        .storeId(storeId)
        .userId(userId)
        .pickupAddress(pickupAddress)
        .shippingAddress(new Address())
        .build();
  }

  @Nested
  @DisplayName("handleOrderPreparing Tests")
  class HandleOrderPreparingTests {

    @Test
    @DisplayName("should process event and delegate to assignment service successfully")
    void shouldProcessEventSuccessfully() {
      // Arrange
      when(idempotentEventRepository.saveAndFlush(any(IdempotentEvent.class)))
          .thenReturn(new IdempotentEvent());

      // Act
      subscriber.handleOrderPreparing(contract);

      // Assert
      ArgumentCaptor<IdempotentEvent> captor = ArgumentCaptor.forClass(IdempotentEvent.class);
      verify(idempotentEventRepository).saveAndFlush(captor.capture());

      IdempotentEvent savedEvent = captor.getValue();
      assertThat(savedEvent.getEventKey()).isEqualTo("ASSIGN_COURIER:" + orderId);
      assertThat(savedEvent.getCreatedAt()).isNotNull();

      verify(assignmentService).assignCourierToOrder(contract);
    }

    @Test
    @DisplayName("should skip processing when event already processed (idempotency)")
    void shouldSkipWhenEventAlreadyProcessed() {
      // Arrange
      when(idempotentEventRepository.saveAndFlush(any(IdempotentEvent.class)))
          .thenThrow(new DataIntegrityViolationException("Duplicate key"));

      // Act
      subscriber.handleOrderPreparing(contract);

      // Assert
      verify(idempotentEventRepository).saveAndFlush(any(IdempotentEvent.class));
      verify(assignmentService, never()).assignCourierToOrder(any());
    }

    @Test
    @DisplayName("should handle race condition with First Writer Wins pattern")
    void shouldHandleRaceCondition() {
      // Arrange - Simulate two threads processing same event
      when(idempotentEventRepository.saveAndFlush(any(IdempotentEvent.class)))
          .thenThrow(new DataIntegrityViolationException("Duplicate key"));

      // Act
      subscriber.handleOrderPreparing(contract);

      // Assert - Should not throw exception, just skip
      verify(assignmentService, never()).assignCourierToOrder(contract);
    }

    @Test
    @DisplayName("should use correct event key format")
    void shouldUseCorrectEventKeyFormat() {
      // Arrange
      when(idempotentEventRepository.saveAndFlush(any(IdempotentEvent.class)))
          .thenReturn(new IdempotentEvent());

      // Act
      subscriber.handleOrderPreparing(contract);

      // Assert
      ArgumentCaptor<IdempotentEvent> captor = ArgumentCaptor.forClass(IdempotentEvent.class);
      verify(idempotentEventRepository).saveAndFlush(captor.capture());

      String expectedKey = "ASSIGN_COURIER:" + orderId;
      assertThat(captor.getValue().getEventKey()).isEqualTo(expectedKey);
    }
  }
}
