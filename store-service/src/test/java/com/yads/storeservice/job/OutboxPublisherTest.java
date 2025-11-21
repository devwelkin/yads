package com.yads.storeservice.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yads.storeservice.model.OutboxEvent;
import com.yads.storeservice.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
        @Mock
        private ObjectMapper objectMapper;

        @InjectMocks
        private OutboxPublisher outboxPublisher;

        private OutboxEvent event1;
        private OutboxEvent event2;
        private UUID eventId1;
        private UUID eventId2;

        @BeforeEach
        void setUp() {
                eventId1 = UUID.randomUUID();
                eventId2 = UUID.randomUUID();

                event1 = OutboxEvent.builder()
                                .id(eventId1)
                                .aggregateType("ORDER")
                                .aggregateId("order-123")
                                .type("order.stock_reserved")
                                .payload("{\"orderId\":\"order-123\",\"storeId\":\"store-456\"}")
                                .createdAt(LocalDateTime.now().minusMinutes(5))
                                .processed(false)
                                .build();

                event2 = OutboxEvent.builder()
                                .id(eventId2)
                                .aggregateType("PRODUCT")
                                .aggregateId("product-789")
                                .type("product.created")
                                .payload("{\"productId\":\"product-789\",\"name\":\"Test Product\"}")
                                .createdAt(LocalDateTime.now().minusMinutes(3))
                                .processed(false)
                                .build();
        }

        @Nested
        @DisplayName("Batch Processing Tests")
        class BatchProcessingTests {

                @Test
                @DisplayName("should fetch top 50 unprocessed events ordered by createdAt")
                void shouldFetchTop50UnprocessedEventsOrderedByCreatedAt() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(event1, event2));

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(outboxRepository).findTop50ByProcessedFalseOrderByCreatedAtAsc();
                }

                @Test
                @DisplayName("should not publish when no events found")
                void shouldNotPublishWhenNoEventsFound() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(Collections.emptyList());

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(outboxRepository).findTop50ByProcessedFalseOrderByCreatedAtAsc();
                        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class),
                                        any(Object.class));
                        verify(outboxRepository, never()).save(any());
                }

                @Test
                @DisplayName("should publish all events in batch")
                void shouldPublishAllEventsInBatch() throws Exception {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(event1, event2));
                        when(objectMapper.readValue(anyString(), eq(Object.class)))
                                        .thenReturn(new Object());

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate, times(2)).convertAndSend(
                                        eq("order_events_exchange"),
                                        anyString(),
                                        any(Object.class));
                }
        }

        @Nested
        @DisplayName("Event Publishing Tests")
        class EventPublishingTests {

                @Test
                @DisplayName("should deserialize payload using ObjectMapper")
                void shouldDeserializePayloadUsingObjectMapper() throws Exception {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(event1));
                        Object payloadObj = new Object();
                        when(objectMapper.readValue(event1.getPayload(), Object.class))
                                        .thenReturn(payloadObj);

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(objectMapper).readValue(event1.getPayload(), Object.class);
                        verify(rabbitTemplate).convertAndSend(
                                        eq("order_events_exchange"),
                                        eq(event1.getType()),
                                        eq(payloadObj));
                }

                @Test
                @DisplayName("should publish to correct exchange with event type as routing key")
                void shouldPublishToCorrectExchangeWithEventTypeAsRoutingKey() throws Exception {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(event1));
                        Object payloadObj = new Object();
                        when(objectMapper.readValue(anyString(), eq(Object.class)))
                                        .thenReturn(payloadObj);

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate).convertAndSend(
                                        "order_events_exchange",
                                        "order.stock_reserved",
                                        payloadObj);
                }

                @Test
                @DisplayName("should mark event as processed after successful publish")
                void shouldMarkEventAsProcessedAfterSuccessfulPublish() throws Exception {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(event1));
                        when(objectMapper.readValue(anyString(), eq(Object.class)))
                                        .thenReturn(new Object());

                        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(outboxRepository).save(eventCaptor.capture());
                        OutboxEvent savedEvent = eventCaptor.getValue();
                        assertThat(savedEvent.getId()).isEqualTo(eventId1);
                        assertThat(savedEvent.isProcessed()).isTrue();
                }
        }

        @Nested
        @DisplayName("Error Handling Tests")
        class ErrorHandlingTests {

                @Test
                @DisplayName("should continue processing remaining events when one fails")
                void shouldContinueProcessingRemainingEventsWhenOneFails() throws Exception {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(event1, event2));

                        // First event fails to deserialize
                        when(objectMapper.readValue(event1.getPayload(), Object.class))
                                        .thenThrow(new RuntimeException("JSON parse error"));

                        // Second event succeeds
                        when(objectMapper.readValue(event2.getPayload(), Object.class))
                                        .thenReturn(new Object());

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert - second event should still be published
                        verify(rabbitTemplate).convertAndSend(
                                        eq("order_events_exchange"),
                                        eq("product.created"),
                                        any(Object.class));

                        // Only second event should be marked as processed
                        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
                        verify(outboxRepository).save(eventCaptor.capture());
                        assertThat(eventCaptor.getValue().getId()).isEqualTo(eventId2);
                }

                @Test
                @DisplayName("should not mark event as processed when publishing fails")
                void shouldNotMarkEventAsProcessedWhenPublishingFails() throws Exception {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(event1));
                        when(objectMapper.readValue(anyString(), eq(Object.class)))
                                        .thenReturn(new Object());
                        doThrow(new RuntimeException("RabbitMQ connection failed"))
                                        .when(rabbitTemplate)
                                        .convertAndSend(any(String.class), any(String.class), any(Object.class));

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert - should not save the event
                        verify(outboxRepository, never()).save(any());
                }

                @Test
                @DisplayName("should not mark event as processed when JSON parsing fails")
                void shouldNotMarkEventAsProcessedWhenJsonParsingFails() throws Exception {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(event1));
                        when(objectMapper.readValue(anyString(), eq(Object.class)))
                                        .thenThrow(new RuntimeException("Invalid JSON"));

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class),
                                        any(Object.class));
                        verify(outboxRepository, never()).save(any());
                }
        }

        @Nested
        @DisplayName("Cleanup Tests")
        class CleanupTests {

                @Test
                @DisplayName("should delete processed events older than 1 day")
                void shouldDeleteProcessedEventsOlderThan1Day() {
                        // Arrange
                        LocalDateTime cutoff = LocalDateTime.now().minusDays(1);

                        OutboxEvent oldEvent1 = OutboxEvent.builder()
                                        .id(UUID.randomUUID())
                                        .processed(true)
                                        .createdAt(cutoff.minusHours(2))
                                        .build();

                        OutboxEvent oldEvent2 = OutboxEvent.builder()
                                        .id(UUID.randomUUID())
                                        .processed(true)
                                        .createdAt(cutoff.minusHours(5))
                                        .build();

                        when(outboxRepository.findTop1000ByProcessedTrueAndCreatedAtBefore(any(LocalDateTime.class)))
                                        .thenReturn(List.of(oldEvent1, oldEvent2))
                                        .thenReturn(Collections.emptyList());

                        // Act
                        outboxPublisher.cleanupProcessedEvents();

                        // Assert - should call findTop1000 twice (batch + empty check)
                        verify(outboxRepository, times(2))
                                        .findTop1000ByProcessedTrueAndCreatedAtBefore(any(LocalDateTime.class));
                        @SuppressWarnings("unchecked")
                        ArgumentCaptor<List<OutboxEvent>> eventsCaptor = ArgumentCaptor.forClass(List.class);
                        verify(outboxRepository).deleteAll(eventsCaptor.capture());
                        List<OutboxEvent> deletedEvents = eventsCaptor.getValue();
                        assertThat(deletedEvents).hasSize(2)
                                        .contains(oldEvent1, oldEvent2);
                }

                @Test
                @DisplayName("should process cleanup in batches of 1000")
                void shouldProcessCleanupInBatchesOf1000() {
                        // Arrange
                        List<OutboxEvent> batch1 = createEventBatch(1000, 1);
                        List<OutboxEvent> batch2 = createEventBatch(500, 1001);

                        when(outboxRepository.findTop1000ByProcessedTrueAndCreatedAtBefore(any(LocalDateTime.class)))
                                        .thenReturn(batch1)
                                        .thenReturn(batch2)
                                        .thenReturn(Collections.emptyList());

                        // Act
                        outboxPublisher.cleanupProcessedEvents();

                        // Assert
                        verify(outboxRepository, times(3))
                                        .findTop1000ByProcessedTrueAndCreatedAtBefore(any(LocalDateTime.class));
                        verify(outboxRepository, times(2)).deleteAll(any());
                }

                @Test
                @DisplayName("should not delete anything when no old processed events found")
                void shouldNotDeleteAnythingWhenNoOldProcessedEventsFound() {
                        // Arrange
                        when(outboxRepository.findTop1000ByProcessedTrueAndCreatedAtBefore(any(LocalDateTime.class)))
                                        .thenReturn(Collections.emptyList());

                        // Act
                        outboxPublisher.cleanupProcessedEvents();

                        // Assert
                        verify(outboxRepository).findTop1000ByProcessedTrueAndCreatedAtBefore(any(LocalDateTime.class));
                        verify(outboxRepository, never()).deleteAll(any());
                }

                @Test
                @DisplayName("should use cutoff time of now minus 1 day")
                void shouldUseCutoffTimeOfNowMinus1Day() {
                        // Arrange
                        when(outboxRepository.findTop1000ByProcessedTrueAndCreatedAtBefore(any(LocalDateTime.class)))
                                        .thenReturn(Collections.emptyList());

                        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

                        // Act
                        LocalDateTime before = LocalDateTime.now().minusDays(1);
                        outboxPublisher.cleanupProcessedEvents();
                        LocalDateTime after = LocalDateTime.now().minusDays(1);

                        // Assert
                        verify(outboxRepository).findTop1000ByProcessedTrueAndCreatedAtBefore(cutoffCaptor.capture());
                        LocalDateTime capturedCutoff = cutoffCaptor.getValue();

                        // Allow 1 second tolerance for test execution time
                        assertThat(capturedCutoff).isBetween(before.minusSeconds(1), after.plusSeconds(1));
                }
        }

        @Nested
        @DisplayName("Integration Scenarios")
        class IntegrationScenarios {

                @Test
                @DisplayName("should handle mixed batch with successes and failures")
                void shouldHandleMixedBatchWithSuccessesAndFailures() throws Exception {
                        // Arrange
                        UUID eventId3 = UUID.randomUUID();
                        OutboxEvent event3 = OutboxEvent.builder()
                                        .id(eventId3)
                                        .type("product.updated")
                                        .payload("{\"productId\":\"product-999\"}")
                                        .processed(false)
                                        .build();

                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(event1, event2, event3));

                        // event1 succeeds
                        when(objectMapper.readValue(event1.getPayload(), Object.class))
                                        .thenReturn(new Object());

                        // event2 fails JSON parsing
                        when(objectMapper.readValue(event2.getPayload(), Object.class))
                                        .thenThrow(new RuntimeException("Parse error"));

                        // event3 succeeds
                        when(objectMapper.readValue(event3.getPayload(), Object.class))
                                        .thenReturn(new Object());

                        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate, times(2)).convertAndSend(
                                        eq("order_events_exchange"),
                                        anyString(),
                                        any(Object.class));

                        // Verify event1 and event3 marked as processed, not event2
                        verify(outboxRepository, times(2)).save(eventCaptor.capture());
                        List<OutboxEvent> savedEvents = eventCaptor.getAllValues();
                        assertThat(savedEvents).extracting(OutboxEvent::getId)
                                        .containsExactlyInAnyOrder(eventId1, eventId3);
                }
        }

        // Helper method to create batch of events for testing
        private List<OutboxEvent> createEventBatch(int size, int startId) {
                List<OutboxEvent> batch = new ArrayList<>();
                LocalDateTime cutoff = LocalDateTime.now().minusDays(2);

                for (int i = 0; i < size; i++) {
                        batch.add(OutboxEvent.builder()
                                        .id(UUID.randomUUID())
                                        .processed(true)
                                        .createdAt(cutoff)
                                        .build());
                }
                return batch;
        }
}
