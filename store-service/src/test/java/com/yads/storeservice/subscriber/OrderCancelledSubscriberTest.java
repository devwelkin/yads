package com.yads.storeservice.subscriber;

import com.yads.common.contracts.OrderCancelledContract;
import com.yads.common.dto.BatchReserveItem;
import com.yads.common.dto.BatchReserveStockRequest;
import com.yads.storeservice.model.IdempotentEvent;
import com.yads.storeservice.repository.IdempotentEventRepository;
import com.yads.storeservice.services.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderCancelledSubscriber Unit Tests")
class OrderCancelledSubscriberTest {

  @Mock
  private ProductService productService;
  @Mock
  private IdempotentEventRepository idempotentEventRepository;

  private OrderCancelledSubscriber subscriber;
  private OrderCancelledSubscriber subscriberSpy;

  private UUID orderId;
  private UUID storeId;
  private UUID userId;
  private UUID productId;
  private OrderCancelledContract requestContract;

  @BeforeEach
  void setUp() {
    orderId = UUID.randomUUID();
    storeId = UUID.randomUUID();
    userId = UUID.randomUUID();
    productId = UUID.randomUUID();

    BatchReserveItem item = new BatchReserveItem(productId, 2);

    requestContract = OrderCancelledContract.builder()
        .orderId(orderId)
        .storeId(storeId)
        .userId(userId)
        .oldStatus("PREPARING")
        .items(List.of(item))
        .build();

    // Create real instance and spy for self-injection proxy
    subscriber = new OrderCancelledSubscriber(
        productService,
        idempotentEventRepository);
    subscriberSpy = spy(subscriber);

    // Inject the spy into the self field using reflection
    ReflectionTestUtils.setField(subscriber, "self", subscriberSpy);
  }

  @Nested
  @DisplayName("Idempotency Tests")
  class IdempotencyTests {

    @Test
    @DisplayName("should create idempotency key on first processing")
    void shouldCreateIdempotencyKeyOnFirstProcessing() {
      // Arrange
      String eventKey = "CANCEL_ORDER:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      doNothing().when(productService).batchRestoreStock(any());

      // Act
      subscriber.handleOrderCancelled(requestContract);

      // Assert
      verify(subscriberSpy).tryCreateIdempotencyKey(eventKey);
      verify(productService).batchRestoreStock(any());
    }

    @Test
    @DisplayName("should skip processing when event already processed")
    void shouldSkipProcessingWhenEventAlreadyProcessed() {
      // Arrange
      String eventKey = "CANCEL_ORDER:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(false);

      // Act
      subscriber.handleOrderCancelled(requestContract);

      // Assert
      verify(subscriberSpy).tryCreateIdempotencyKey(eventKey);
      verify(productService, never()).batchRestoreStock(any());
    }

    @Test
    @DisplayName("should handle race condition with DataIntegrityViolationException")
    void shouldHandleRaceConditionWithDataIntegrityViolationException() {
      // Arrange - testing tryCreateIdempotencyKey directly
      String eventKey = "CANCEL_ORDER:test-race";
      when(idempotentEventRepository.existsById(eventKey)).thenReturn(false);
      when(idempotentEventRepository.saveAndFlush(any(IdempotentEvent.class)))
          .thenThrow(new DataIntegrityViolationException("duplicate key"));

      // Act
      boolean result = subscriber.tryCreateIdempotencyKey(eventKey);

      // Assert
      assertThat(result).isFalse();
      verify(idempotentEventRepository).existsById(eventKey);
      verify(idempotentEventRepository).saveAndFlush(any(IdempotentEvent.class));
    }

    @Test
    @DisplayName("should return false when idempotency key already exists in fast path")
    void shouldReturnFalseWhenIdempotencyKeyAlreadyExistsInFastPath() {
      // Arrange - testing tryCreateIdempotencyKey directly
      String eventKey = "CANCEL_ORDER:test-fast-path";
      when(idempotentEventRepository.existsById(eventKey)).thenReturn(true);

      // Act
      boolean result = subscriber.tryCreateIdempotencyKey(eventKey);

      // Assert
      assertThat(result).isFalse();
      verify(idempotentEventRepository).existsById(eventKey);
      verify(idempotentEventRepository, never()).saveAndFlush(any());
    }
  }

  @Nested
  @DisplayName("Ghost Inventory Prevention Tests")
  class GhostInventoryPreventionTests {

    @Test
    @DisplayName("should restore stock when oldStatus is PREPARING")
    void shouldRestoreStockWhenOldStatusIsPreparing() {
      // Arrange
      String eventKey = "CANCEL_ORDER:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      requestContract.setOldStatus("PREPARING");

      ArgumentCaptor<BatchReserveStockRequest> requestCaptor = ArgumentCaptor.forClass(BatchReserveStockRequest.class);

      // Act
      subscriber.handleOrderCancelled(requestContract);

      // Assert
      verify(productService).batchRestoreStock(requestCaptor.capture());
      BatchReserveStockRequest capturedRequest = requestCaptor.getValue();
      assertThat(capturedRequest.getStoreId()).isEqualTo(storeId);
      assertThat(capturedRequest.getItems()).hasSize(1);
      assertThat(capturedRequest.getItems().get(0).getProductId()).isEqualTo(productId);
      assertThat(capturedRequest.getItems().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("should restore stock when oldStatus is ON_THE_WAY")
    void shouldRestoreStockWhenOldStatusIsOnTheWay() {
      // Arrange
      String eventKey = "CANCEL_ORDER:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      requestContract.setOldStatus("ON_THE_WAY");

      // Act
      subscriber.handleOrderCancelled(requestContract);

      // Assert
      verify(productService).batchRestoreStock(any());
    }

    @Test
    @DisplayName("should NOT restore stock when oldStatus is PENDING")
    void shouldNotRestoreStockWhenOldStatusIsPending() {
      // Arrange
      String eventKey = "CANCEL_ORDER:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      requestContract.setOldStatus("PENDING");

      // Act
      subscriber.handleOrderCancelled(requestContract);

      // Assert
      verify(productService, never()).batchRestoreStock(any());
    }

    @Test
    @DisplayName("should NOT restore stock when oldStatus is CANCELLED")
    void shouldNotRestoreStockWhenOldStatusIsCancelled() {
      // Arrange
      String eventKey = "CANCEL_ORDER:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      requestContract.setOldStatus("CANCELLED");

      // Act
      subscriber.handleOrderCancelled(requestContract);

      // Assert
      verify(productService, never()).batchRestoreStock(any());
    }

    @Test
    @DisplayName("should NOT restore stock when oldStatus is DELIVERED")
    void shouldNotRestoreStockWhenOldStatusIsDelivered() {
      // Arrange
      String eventKey = "CANCEL_ORDER:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      requestContract.setOldStatus("DELIVERED");

      // Act
      subscriber.handleOrderCancelled(requestContract);

      // Assert
      verify(productService, never()).batchRestoreStock(any());
    }
  }

  @Nested
  @DisplayName("Batch Restore Request Tests")
  class BatchRestoreRequestTests {

    @Test
    @DisplayName("should pass correct storeId to batchRestoreStock")
    void shouldPassCorrectStoreIdToBatchRestoreStock() {
      // Arrange
      String eventKey = "CANCEL_ORDER:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);

      ArgumentCaptor<BatchReserveStockRequest> requestCaptor = ArgumentCaptor.forClass(BatchReserveStockRequest.class);

      // Act
      subscriber.handleOrderCancelled(requestContract);

      // Assert
      verify(productService).batchRestoreStock(requestCaptor.capture());
      assertThat(requestCaptor.getValue().getStoreId()).isEqualTo(storeId);
    }

    @Test
    @DisplayName("should pass all items to batchRestoreStock")
    void shouldPassAllItemsToBatchRestoreStock() {
      // Arrange
      String eventKey = "CANCEL_ORDER:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);

      UUID product2Id = UUID.randomUUID();
      BatchReserveItem item1 = new BatchReserveItem(productId, 2);
      BatchReserveItem item2 = new BatchReserveItem(product2Id, 5);

      requestContract.setItems(List.of(item1, item2));

      ArgumentCaptor<BatchReserveStockRequest> requestCaptor = ArgumentCaptor.forClass(BatchReserveStockRequest.class);

      // Act
      subscriber.handleOrderCancelled(requestContract);

      // Assert
      verify(productService).batchRestoreStock(requestCaptor.capture());
      BatchReserveStockRequest capturedRequest = requestCaptor.getValue();
      assertThat(capturedRequest.getItems()).hasSize(2);
      assertThat(capturedRequest.getItems().get(0).getProductId()).isEqualTo(productId);
      assertThat(capturedRequest.getItems().get(0).getQuantity()).isEqualTo(2);
      assertThat(capturedRequest.getItems().get(1).getProductId()).isEqualTo(product2Id);
      assertThat(capturedRequest.getItems().get(1).getQuantity()).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("Error Handling Tests")
  class ErrorHandlingTests {

    @Test
    @DisplayName("should propagate exception when productService fails")
    void shouldPropagateExceptionWhenProductServiceFails() {
      // Arrange
      String eventKey = "CANCEL_ORDER:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      doThrow(new RuntimeException("Database connection failed"))
          .when(productService).batchRestoreStock(any());

      // Act & Assert
      assertThatThrownBy(() -> subscriber.handleOrderCancelled(requestContract))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Database connection failed");
    }

    @Test
    @DisplayName("should propagate exception when idempotency check fails")
    void shouldPropagateExceptionWhenIdempotencyCheckFails() {
      // Arrange
      String eventKey = "CANCEL_ORDER:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey))
          .thenThrow(new RuntimeException("Database error"));

      // Act & Assert
      assertThatThrownBy(() -> subscriber.handleOrderCancelled(requestContract))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Database error");

      verify(productService, never()).batchRestoreStock(any());
    }
  }
}
