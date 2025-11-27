package com.yads.storeservice.job;

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
import org.springframework.amqp.core.Message;
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

        @InjectMocks
        private OutboxPublisher outboxPublisher;

        private OutboxEvent orderEvent;
        private OutboxEvent productEvent;
        private UUID orderEventId;
        private UUID productEventId;

        @BeforeEach
        void setUp() {
                orderEventId = UUID.randomUUID();
                productEventId = UUID.randomUUID();

                orderEvent = OutboxEvent.builder()
                                .id(orderEventId)
                                .aggregateType("ORDER")
                                .aggregateId("order-123")
                                .type("order.stock_reserved")
                                .payload("{\"orderId\":\"order-123\",\"storeId\":\"store-456\"}")
                                .createdAt(LocalDateTime.now().minusMinutes(5))
                                .processed(false)
                                .build();

                productEvent = OutboxEvent.builder()
                                .id(productEventId)
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
                                        .thenReturn(List.of(orderEvent, productEvent));

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
                        verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
                        verify(outboxRepository, never()).save(any());
                }

                @Test
                @DisplayName("should publish all events in batch")
                void shouldPublishAllEventsInBatch() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(orderEvent, productEvent));

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate, times(2)).send(anyString(), anyString(), any(Message.class));
                }
        }

        @Nested
        @DisplayName("Event Publishing Tests")
        class EventPublishingTests {

                @Test
                @DisplayName("should send raw JSON payload as message body")
                void shouldSendRawJsonPayloadAsMessageBody() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(orderEvent));

                        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate).send(anyString(), anyString(), messageCaptor.capture());
                        Message capturedMessage = messageCaptor.getValue();
                        String body = new String(capturedMessage.getBody());
                        assertThat(body).isEqualTo(orderEvent.getPayload());
                }

                @Test
                @DisplayName("should publish order events to order_events_exchange")
                void shouldPublishOrderEventsToOrderEventsExchange() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(orderEvent));

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate).send(
                                        eq("order_events_exchange"),
                                        eq("order.stock_reserved"),
                                        any(Message.class));
                }

                @Test
                @DisplayName("should publish product events to store_events_exchange")
                void shouldPublishProductEventsToStoreEventsExchange() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(productEvent));

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate).send(
                                        eq("store_events_exchange"),
                                        eq("product.created"),
                                        any(Message.class));
                }

                @Test
                @DisplayName("should set __TypeId__ header for product events")
                void shouldSetTypeIdHeaderForProductEvents() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(productEvent));

                        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate).send(anyString(), anyString(), messageCaptor.capture());
                        Message capturedMessage = messageCaptor.getValue();
                        assertThat((String) capturedMessage.getMessageProperties().getHeader("__TypeId__"))
                                        .isEqualTo("com.yads.common.contracts.ProductEventDto");
                }

                @Test
                @DisplayName("should set __TypeId__ header as UUID for product.deleted events")
                void shouldSetTypeIdHeaderAsUuidForProductDeletedEvents() {
                        // Arrange
                        OutboxEvent deleteEvent = OutboxEvent.builder()
                                        .id(UUID.randomUUID())
                                        .aggregateType("PRODUCT")
                                        .aggregateId("product-123")
                                        .type("product.deleted")
                                        .payload("\"550e8400-e29b-41d4-a716-446655440000\"")
                                        .createdAt(LocalDateTime.now())
                                        .processed(false)
                                        .build();

                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(deleteEvent));

                        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate).send(anyString(), anyString(), messageCaptor.capture());
                        Message capturedMessage = messageCaptor.getValue();
                        assertThat((String) capturedMessage.getMessageProperties().getHeader("__TypeId__"))
                                        .isEqualTo("java.util.UUID");
                }

                @Test
                @DisplayName("should not set __TypeId__ header for order events")
                void shouldNotSetTypeIdHeaderForOrderEvents() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(orderEvent));

                        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate).send(anyString(), anyString(), messageCaptor.capture());
                        Message capturedMessage = messageCaptor.getValue();
                        assertThat((String) capturedMessage.getMessageProperties().getHeader("__TypeId__")).isNull();
                }

                @Test
                @DisplayName("should set content type as application/json")
                void shouldSetContentTypeAsApplicationJson() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(orderEvent));

                        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate).send(anyString(), anyString(), messageCaptor.capture());
                        Message capturedMessage = messageCaptor.getValue();
                        assertThat(capturedMessage.getMessageProperties().getContentType())
                                        .isEqualTo("application/json");
                }

                @Test
                @DisplayName("should mark event as processed after successful publish")
                void shouldMarkEventAsProcessedAfterSuccessfulPublish() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(orderEvent));

                        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(outboxRepository).save(eventCaptor.capture());
                        OutboxEvent savedEvent = eventCaptor.getValue();
                        assertThat(savedEvent.getId()).isEqualTo(orderEventId);
                        assertThat(savedEvent.isProcessed()).isTrue();
                }
        }

        @Nested
        @DisplayName("Error Handling Tests")
        class ErrorHandlingTests {

                @Test
                @DisplayName("should continue processing remaining events when one fails")
                void shouldContinueProcessingRemainingEventsWhenOneFails() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(orderEvent, productEvent));

                        // First event fails
                        doThrow(new RuntimeException("RabbitMQ error"))
                                        .doNothing()
                                        .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert - second event should still be published
                        verify(rabbitTemplate, times(2)).send(anyString(), anyString(), any(Message.class));

                        // Only second event should be marked as processed
                        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
                        verify(outboxRepository).save(eventCaptor.capture());
                        assertThat(eventCaptor.getValue().getId()).isEqualTo(productEventId);
                }

                @Test
                @DisplayName("should not mark event as processed when publishing fails")
                void shouldNotMarkEventAsProcessedWhenPublishingFails() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(orderEvent));
                        doThrow(new RuntimeException("RabbitMQ connection failed"))
                                        .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert - should not save the event
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
                void shouldHandleMixedBatchWithSuccessesAndFailures() {
                        // Arrange
                        UUID eventId3 = UUID.randomUUID();
                        OutboxEvent event3 = OutboxEvent.builder()
                                        .id(eventId3)
                                        .aggregateType("PRODUCT")
                                        .aggregateId("product-999")
                                        .type("product.updated")
                                        .payload("{\"productId\":\"product-999\"}")
                                        .createdAt(LocalDateTime.now())
                                        .processed(false)
                                        .build();

                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(orderEvent, productEvent, event3));

                        // productEvent (second) fails
                        doNothing()
                                        .doThrow(new RuntimeException("RabbitMQ error"))
                                        .doNothing()
                                        .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

                        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert - all 3 attempts should be made
                        verify(rabbitTemplate, times(3)).send(anyString(), anyString(), any(Message.class));

                        // Verify orderEvent and event3 marked as processed, not productEvent
                        verify(outboxRepository, times(2)).save(eventCaptor.capture());
                        List<OutboxEvent> savedEvents = eventCaptor.getAllValues();
                        assertThat(savedEvents).extracting(OutboxEvent::getId)
                                        .containsExactlyInAnyOrder(orderEventId, eventId3);
                }

                @Test
                @DisplayName("should route events to correct exchanges based on event type")
                void shouldRouteEventsToCorrectExchangesBasedOnEventType() {
                        // Arrange
                        when(outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc())
                                        .thenReturn(List.of(orderEvent, productEvent));

                        // Act
                        outboxPublisher.publishOutboxEvents();

                        // Assert
                        verify(rabbitTemplate).send(
                                        eq("order_events_exchange"),
                                        eq("order.stock_reserved"),
                                        any(Message.class));
                        verify(rabbitTemplate).send(
                                        eq("store_events_exchange"),
                                        eq("product.created"),
                                        any(Message.class));
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
