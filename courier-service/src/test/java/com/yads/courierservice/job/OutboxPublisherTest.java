package com.yads.courierservice.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yads.common.contracts.CourierAssignedContract;
import com.yads.courierservice.model.OutboxEvent;
import com.yads.courierservice.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublisher Unit Tests")
class OutboxPublisherTest {

  @Mock
  private OutboxRepository outboxRepository;

  @Mock
  private RabbitTemplate rabbitTemplate;

  @Spy
  private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks
  private OutboxPublisher publisher;

  @BeforeEach
  void setUp() {
    objectMapper.registerModule(new JavaTimeModule());
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Nested
  @DisplayName("publishOutboxEvents Tests")
  class PublishOutboxEventsTests {

    @Test
    @DisplayName("should fetch top 50 unprocessed events in order by createdAt")
    void shouldFetchTop50UnprocessedEvents() {
      // Arrange
      when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
          .thenReturn(List.of());

      // Act
      publisher.publishOutboxEvents();

      // Assert
      verify(outboxRepository).findTop50ByProcessedFalseOrderByCreatedAtAsc();
    }

    @Test
    @DisplayName("should publish all events successfully")
    void shouldPublishAllEventsSuccessfully() throws Exception {
      // Arrange
      OutboxEvent event1 = createOutboxEvent("courier.assigned", false);
      OutboxEvent event2 = createOutboxEvent("courier.assignment.failed", false);

      when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
          .thenReturn(List.of(event1, event2));

      // Act
      publisher.publishOutboxEvents();

      // Assert
      verify(rabbitTemplate, times(2)).convertAndSend(
          eq("courier_events_exchange"),
          anyString(),
          any(Object.class));
    }

    @Test
    @DisplayName("should mark events as processed after publishing")
    void shouldMarkEventsAsProcessedAfterPublishing() {
      // Arrange
      OutboxEvent event = createOutboxEvent("courier.assigned", false);

      when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
          .thenReturn(List.of(event));

      // Act
      publisher.publishOutboxEvents();

      // Assert
      ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
      verify(outboxRepository).save(captor.capture());

      assertThat(captor.getValue().isProcessed()).isTrue();
      assertThat(captor.getValue().getId()).isEqualTo(event.getId());
    }

    @Test
    @DisplayName("should continue processing when one event fails")
    void shouldContinueProcessingWhenOneFails() throws Exception {
      // Arrange
      OutboxEvent event1 = createOutboxEvent("courier.assigned", false);
      OutboxEvent event2 = createOutboxEvent("courier.assigned", false);

      when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
          .thenReturn(List.of(event1, event2));

      // First event fails, second succeeds
      doThrow(new RuntimeException("RabbitMQ connection lost"))
          .doNothing()
          .when(rabbitTemplate).convertAndSend(eq("courier_events_exchange"), anyString(), any(Object.class));

      // Act
      publisher.publishOutboxEvents();

      // Assert - Both events should be attempted
      verify(rabbitTemplate, times(2)).convertAndSend(eq("courier_events_exchange"), anyString(), any(Object.class));

      // Only event2 should be marked as processed
      verify(outboxRepository, times(1)).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("should do nothing when no events to process")
    void shouldDoNothingWhenNoEvents() {
      // Arrange
      when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
          .thenReturn(List.of());

      // Act
      publisher.publishOutboxEvents();

      // Assert
      verify(rabbitTemplate, never()).convertAndSend(eq("courier_events_exchange"), anyString(), any(Object.class));
      verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("should deserialize payload with ObjectMapper")
    void shouldDeserializePayloadWithObjectMapper() throws Exception {
      // Arrange
      OutboxEvent event = createOutboxEvent("courier.assigned", false);

      when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
          .thenReturn(List.of(event));

      // Act
      publisher.publishOutboxEvents();

      // Assert
      verify(objectMapper).readValue(eq(event.getPayload()), eq(Object.class));
    }

    @Test
    @DisplayName("should publish to correct exchange with correct routing key")
    void shouldPublishToCorrectExchangeAndRoutingKey() {
      // Arrange
      OutboxEvent event = createOutboxEvent("courier.assigned", false);

      when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
          .thenReturn(List.of(event));

      // Act
      publisher.publishOutboxEvents();

      // Assert
      verify(rabbitTemplate).convertAndSend(
          eq("courier_events_exchange"),
          eq("courier.assigned"),
          any(Object.class));
    }

    @Test
    @DisplayName("should not mark event as processed when publishing fails")
    void shouldNotMarkAsProcessedWhenPublishingFails() {
      // Arrange
      OutboxEvent event = createOutboxEvent("courier.assigned", false);

      when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
          .thenReturn(List.of(event));
      doThrow(new RuntimeException("RabbitMQ error"))
          .when(rabbitTemplate).convertAndSend(eq("courier_events_exchange"), anyString(), any(Object.class));

      // Act
      publisher.publishOutboxEvents();

      // Assert
      verify(outboxRepository, never()).save(any());
      assertThat(event.isProcessed()).isFalse();
    }
  }

  @Nested
  @DisplayName("cleanupProcessedEvents Tests")
  class CleanupProcessedEventsTests {

    @Test
    @DisplayName("should delete old processed events in batches")
    void shouldDeleteOldProcessedEventsInBatches() {
      // Arrange
      List<OutboxEvent> batch1 = createBatch(1000, true);
      List<OutboxEvent> batch2 = createBatch(500, true);

      when(outboxRepository.findTop1000ByProcessedTrueAndCreatedAtBefore(any()))
          .thenReturn(batch1)
          .thenReturn(batch2)
          .thenReturn(List.of());

      // Act
      publisher.cleanupProcessedEvents();

      // Assert
      verify(outboxRepository, times(3)).findTop1000ByProcessedTrueAndCreatedAtBefore(any());
      verify(outboxRepository, times(2)).deleteAll(anyList());
    }

    @Test
    @DisplayName("should only delete processed events older than cutoff")
    void shouldOnlyDeleteProcessedEventsOlderThanCutoff() {
      // Arrange
      when(outboxRepository.findTop1000ByProcessedTrueAndCreatedAtBefore(any()))
          .thenReturn(List.of());

      // Act
      publisher.cleanupProcessedEvents();

      // Assert
      ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
      verify(outboxRepository).findTop1000ByProcessedTrueAndCreatedAtBefore(captor.capture());

      LocalDateTime cutoff = captor.getValue();
      assertThat(cutoff).isBefore(LocalDateTime.now());
      assertThat(cutoff).isAfter(LocalDateTime.now().minusDays(2));
    }

    @Test
    @DisplayName("should stop when no more events to delete")
    void shouldStopWhenNoMoreEvents() {
      // Arrange
      when(outboxRepository.findTop1000ByProcessedTrueAndCreatedAtBefore(any()))
          .thenReturn(List.of());

      // Act
      publisher.cleanupProcessedEvents();

      // Assert
      verify(outboxRepository, times(1)).findTop1000ByProcessedTrueAndCreatedAtBefore(any());
      verify(outboxRepository, never()).deleteAll(anyList());
    }
  }

  private OutboxEvent createOutboxEvent(String type, boolean processed) {
    CourierAssignedContract contract = CourierAssignedContract.builder()
        .orderId(UUID.randomUUID())
        .courierId(UUID.randomUUID())
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .build();

    String payload;
    try {
      payload = objectMapper.writeValueAsString(contract);
    } catch (Exception e) {
      payload = "{}";
    }

    return OutboxEvent.builder()
        .id(UUID.randomUUID())
        .aggregateType("COURIER")
        .aggregateId(UUID.randomUUID().toString())
        .type(type)
        .payload(payload)
        .createdAt(LocalDateTime.now().minusHours(2))
        .processed(processed)
        .build();
  }

  private List<OutboxEvent> createBatch(int size, boolean processed) {
    return java.util.stream.IntStream.range(0, size)
        .mapToObj(i -> createOutboxEvent("courier.assigned", processed))
        .toList();
  }
}
