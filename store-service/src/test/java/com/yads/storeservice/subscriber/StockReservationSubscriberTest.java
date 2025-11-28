package com.yads.storeservice.subscriber;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yads.common.contracts.StockReservationFailedContract;
import com.yads.common.contracts.StockReservationRequestContract;
import com.yads.common.contracts.StockReservedContract;
import com.yads.common.dto.BatchReserveItem;
import com.yads.common.dto.BatchReserveStockRequest;
import com.yads.common.exception.InsufficientStockException;
import com.yads.common.model.Address;
import com.yads.storeservice.model.IdempotentEvent;
import com.yads.storeservice.model.OutboxEvent;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.repository.IdempotentEventRepository;
import com.yads.storeservice.repository.OutboxRepository;
import com.yads.storeservice.repository.StoreRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockReservationSubscriber Unit Tests")
class StockReservationSubscriberTest {

  @Mock
  private ProductService productService;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private OutboxRepository outboxRepository;
  @Mock
  private ObjectMapper objectMapper;
  @Mock
  private IdempotentEventRepository idempotentEventRepository;

  private StockReservationSubscriber subscriber;
  private StockReservationSubscriber subscriberSpy;

  private UUID orderId;
  private UUID storeId;
  private UUID userId;
  private UUID productId;
  private Store store;
  private Address address;
  private StockReservationRequestContract requestContract;

  @BeforeEach
  void setUp() {
    orderId = UUID.randomUUID();
    storeId = UUID.randomUUID();
    userId = UUID.randomUUID();
    productId = UUID.randomUUID();

    address = new Address();
    address.setStreet("123 Main St");
    address.setCity("Test City");
    address.setState("TS");
    address.setPostalCode("12345");

    store = new Store();
    store.setId(storeId);
    store.setName("Test Store");
    store.setAddress(address);

    BatchReserveItem item = new BatchReserveItem(productId, 2);

    requestContract = StockReservationRequestContract.builder()
        .orderId(orderId)
        .storeId(storeId)
        .userId(userId)
        .items(List.of(item))
        .shippingAddress(new Address())
        .build();

    // Create real instance and spy
    subscriber = new StockReservationSubscriber(
        productService,
        storeRepository,
        outboxRepository,
        objectMapper,
        idempotentEventRepository);
    subscriberSpy = spy(subscriber);

    // Inject the spy into the self field using reflection
    ReflectionTestUtils.setField(subscriber, "self", subscriberSpy);
  }

  @Nested
  @DisplayName("Idempotency Tests")
  class IdempotencyTests {

    @Test
    @DisplayName("should create idempotency key successfully when not exists")
    void shouldCreateIdempotencyKeySuccessfully() {
      // Arrange
      String eventKey = "TEST_KEY";
      when(idempotentEventRepository.existsById(eventKey)).thenReturn(false);
      when(idempotentEventRepository.saveAndFlush(any(IdempotentEvent.class)))
          .thenReturn(new IdempotentEvent());

      // Act
      boolean result = subscriber.tryCreateIdempotencyKey(eventKey);

      // Assert
      assertThat(result).isTrue();
      verify(idempotentEventRepository).saveAndFlush(argThat(event -> event.getEventKey().equals(eventKey)));
    }

    @Test
    @DisplayName("should return false when idempotency key already exists")
    void shouldReturnFalseWhenIdempotencyKeyAlreadyExists() {
      // Arrange
      String eventKey = "TEST_KEY";
      when(idempotentEventRepository.existsById(eventKey)).thenReturn(true);

      // Act
      boolean result = subscriber.tryCreateIdempotencyKey(eventKey);

      // Assert
      assertThat(result).isFalse();
      verify(idempotentEventRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("should handle race condition with DataIntegrityViolationException")
    void shouldHandleRaceConditionWithDataIntegrityViolationException() {
      // Arrange
      String eventKey = "TEST_KEY";
      when(idempotentEventRepository.existsById(eventKey)).thenReturn(false);
      when(idempotentEventRepository.saveAndFlush(any(IdempotentEvent.class)))
          .thenThrow(new DataIntegrityViolationException("Duplicate key"));

      // Act
      boolean result = subscriber.tryCreateIdempotencyKey(eventKey);

      // Assert
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("should skip processing when event already processed")
    void shouldSkipProcessingWhenEventAlreadyProcessed() {
      // Arrange
      String eventKey = "RESERVE_STOCK:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(false);

      // Act
      subscriber.handleStockReservationRequest(requestContract);

      // Assert
      verify(productService, never()).batchReserveStock(any());
      verify(outboxRepository, never()).save(any());
    }
  }

  @Nested
  @DisplayName("Success Path Tests")
  class SuccessPathTests {

    @Test
    @DisplayName("should reserve stock successfully and save success event")
    void shouldReserveStockSuccessfullyAndSaveSuccessEvent() throws Exception {
      // Arrange
      String eventKey = "RESERVE_STOCK:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

      String expectedPayload = "{\"orderId\":\"" + orderId + "\"}";
      when(objectMapper.writeValueAsString(any(StockReservedContract.class)))
          .thenReturn(expectedPayload);

      // Act
      subscriber.handleStockReservationRequest(requestContract);

      // Assert
      verify(productService).batchReserveStock(argThat(req -> req.getStoreId().equals(storeId) &&
          req.getItems().size() == 1));
      verify(storeRepository).findById(storeId);

      ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
      verify(outboxRepository).save(eventCaptor.capture());

      OutboxEvent savedEvent = eventCaptor.getValue();
      assertThat(savedEvent.getAggregateType()).isEqualTo("ORDER");
      assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
      assertThat(savedEvent.getType()).isEqualTo("order.stock_reserved");
      assertThat(savedEvent.isProcessed()).isFalse();
    }

    @Test
    @DisplayName("should include pickup address in success event")
    void shouldIncludePickupAddressInSuccessEvent() throws Exception {
      // Arrange
      String eventKey = "RESERVE_STOCK:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(objectMapper.writeValueAsString(any(StockReservedContract.class)))
          .thenReturn("{}");

      ArgumentCaptor<StockReservedContract> contractCaptor = ArgumentCaptor.forClass(StockReservedContract.class);

      // Act
      subscriber.handleStockReservationRequest(requestContract);

      // Assert
      verify(objectMapper).writeValueAsString(contractCaptor.capture());
      StockReservedContract contract = contractCaptor.getValue();
      assertThat(contract.getPickupAddress()).isEqualTo(address);
      assertThat(contract.getOrderId()).isEqualTo(orderId);
      assertThat(contract.getStoreId()).isEqualTo(storeId);
    }

    @Test
    @DisplayName("should call productService with correct batch reserve request")
    void shouldCallProductServiceWithCorrectBatchReserveRequest() {
      // Arrange
      String eventKey = "RESERVE_STOCK:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

      ArgumentCaptor<BatchReserveStockRequest> requestCaptor = ArgumentCaptor.forClass(BatchReserveStockRequest.class);

      // Act
      subscriber.handleStockReservationRequest(requestContract);

      // Assert
      verify(productService).batchReserveStock(requestCaptor.capture());
      BatchReserveStockRequest capturedRequest = requestCaptor.getValue();
      assertThat(capturedRequest.getStoreId()).isEqualTo(storeId);
      assertThat(capturedRequest.getItems()).hasSize(1);
      assertThat(capturedRequest.getItems().get(0).getProductId()).isEqualTo(productId);
      assertThat(capturedRequest.getItems().get(0).getQuantity()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("Failure Path Tests")
  class FailurePathTests {

    @Test
    @DisplayName("should save failure event when stock reservation fails")
    void shouldSaveFailureEventWhenStockReservationFails() {
      // Arrange
      String eventKey = "RESERVE_STOCK:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      doThrow(new InsufficientStockException("Not enough stock"))
          .when(productService).batchReserveStock(any());

      // Act
      subscriber.handleStockReservationRequest(requestContract);

      // Assert
      verify(subscriberSpy).saveFailureEvent(argThat(contract -> contract.getOrderId().equals(orderId) &&
          contract.getReason().equals("Not enough stock")));
    }

    @Test
    @DisplayName("should not save success event when stock reservation fails")
    void shouldNotSaveSuccessEventWhenStockReservationFails() {
      // Arrange
      String eventKey = "RESERVE_STOCK:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      doThrow(new InsufficientStockException("Not enough stock"))
          .when(productService).batchReserveStock(any());

      // Act
      subscriber.handleStockReservationRequest(requestContract);

      // Assert - verify no success event saved (failure event saved via
      // self.saveFailureEvent)
      verify(outboxRepository, never()).save(argThat(event -> event.getType().equals("order.stock_reserved")));
      verify(storeRepository, never()).findById(any());
    }

    @Test
    @DisplayName("should include error reason in failure contract")
    void shouldIncludeErrorReasonInFailureContract() {
      // Arrange
      String eventKey = "RESERVE_STOCK:" + orderId;
      String errorMessage = "Product X has insufficient stock";
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      doThrow(new InsufficientStockException(errorMessage))
          .when(productService).batchReserveStock(any());

      ArgumentCaptor<StockReservationFailedContract> contractCaptor = ArgumentCaptor
          .forClass(StockReservationFailedContract.class);

      // Act
      subscriber.handleStockReservationRequest(requestContract);

      // Assert
      verify(subscriberSpy).saveFailureEvent(contractCaptor.capture());
      StockReservationFailedContract contract = contractCaptor.getValue();
      assertThat(contract.getOrderId()).isEqualTo(orderId);
      assertThat(contract.getUserId()).isEqualTo(userId);
      assertThat(contract.getReason()).isEqualTo(errorMessage);
    }
  }

  @Nested
  @DisplayName("Save Failure Event Tests")
  class SaveFailureEventTests {

    @Test
    @DisplayName("should save failure event to outbox with REQUIRES_NEW transaction")
    void shouldSaveFailureEventToOutboxWithRequiresNewTransaction() throws Exception {
      // Arrange
      StockReservationFailedContract contract = StockReservationFailedContract.builder()
          .orderId(orderId)
          .userId(userId)
          .reason("Test failure")
          .build();

      String expectedPayload = "{\"orderId\":\"" + orderId + "\"}";
      when(objectMapper.writeValueAsString(contract)).thenReturn(expectedPayload);

      // Act
      subscriber.saveFailureEvent(contract);

      // Assert
      ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
      verify(outboxRepository).save(eventCaptor.capture());

      OutboxEvent savedEvent = eventCaptor.getValue();
      assertThat(savedEvent.getAggregateType()).isEqualTo("ORDER");
      assertThat(savedEvent.getAggregateId()).isEqualTo(orderId.toString());
      assertThat(savedEvent.getType()).isEqualTo("order.stock_reservation_failed");
      assertThat(savedEvent.getPayload()).isEqualTo(expectedPayload);
      assertThat(savedEvent.isProcessed()).isFalse();
    }

    @Test
    @DisplayName("should handle exception when saving failure event")
    void shouldHandleExceptionWhenSavingFailureEvent() throws Exception {
      // Arrange
      StockReservationFailedContract contract = StockReservationFailedContract.builder()
          .orderId(orderId)
          .userId(userId)
          .reason("Test failure")
          .build();

      when(objectMapper.writeValueAsString(contract))
          .thenThrow(new RuntimeException("Serialization error"));

      // Act - should not throw exception
      subscriber.saveFailureEvent(contract);

      // Assert - verify error was logged but not propagated
      verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("should serialize failure contract correctly")
    void shouldSerializeFailureContractCorrectly() throws Exception {
      // Arrange
      StockReservationFailedContract contract = StockReservationFailedContract.builder()
          .orderId(orderId)
          .userId(userId)
          .reason("Insufficient stock")
          .build();

      String expectedPayload = "serialized";
      when(objectMapper.writeValueAsString(contract)).thenReturn(expectedPayload);

      // Act
      subscriber.saveFailureEvent(contract);

      // Assert
      verify(objectMapper).writeValueAsString(contract);
      verify(outboxRepository).save(argThat(event -> event.getPayload().equals(expectedPayload)));
    }
  }

  @Nested
  @DisplayName("Store Lookup Tests")
  class StoreLookupTests {

    @Test
    @DisplayName("should throw exception when store not found")
    void shouldThrowExceptionWhenStoreNotFound() {
      // Arrange
      String eventKey = "RESERVE_STOCK:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

      // Act
      subscriber.handleStockReservationRequest(requestContract);

      // Assert
      verify(subscriberSpy).saveFailureEvent(argThat(contract -> contract.getReason().contains("Store not found")));
    }

    @Test
    @DisplayName("should fetch store after successful stock reservation")
    void shouldFetchStoreAfterSuccessfulStockReservation() {
      // Arrange
      String eventKey = "RESERVE_STOCK:" + orderId;
      when(subscriberSpy.tryCreateIdempotencyKey(eventKey)).thenReturn(true);
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

      // Act
      subscriber.handleStockReservationRequest(requestContract);

      // Assert
      verify(productService).batchReserveStock(any());
      verify(storeRepository).findById(storeId);
    }
  }
}
