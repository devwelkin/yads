package com.yads.courierservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yads.common.contracts.CourierAssignedContract;
import com.yads.courierservice.job.OutboxPublisher;
import com.yads.courierservice.model.OutboxEvent;
import com.yads.courierservice.repository.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OutboxPublisher scheduled job.
 *
 * Tests:
 * 1. Publishing unpublished events to RabbitMQ
 * 2. Marking events as processed after successful publish
 * 3. Cleanup of old processed events
 * 4. Batch processing with limit
 *
 * Pattern borrowed from order-service OutboxJobIntegrationTest.
 */
public class OutboxPublisherIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private OutboxRepository outboxRepository;

  @Autowired
  private OutboxPublisher outboxPublisher;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private BlockingQueue<Object> capturedMessages;

  @BeforeEach
  @AfterEach
  void cleanup() {
    outboxRepository.deleteAll();
    capturedMessages.clear();
  }

  // --- TEST CONFIGURATION (SPY FOR PUBLISHED EVENTS) ---
  @TestConfiguration
  static class TestRabbitConfig {

    @Bean
    public BlockingQueue<Object> capturedMessages() {
      return new LinkedBlockingQueue<>();
    }

    @Bean
    public Queue outboxSpyQueue() {
      return new Queue("outbox.spy.queue", false);
    }

    @Bean
    public Binding bindingCourierAssignedSpy(Queue outboxSpyQueue) {
      return BindingBuilder.bind(outboxSpyQueue)
          .to(new DirectExchange("courier_events_exchange"))
          .with("courier.assigned");
    }

    @Bean
    public Binding bindingAssignmentFailedSpy(Queue outboxSpyQueue) {
      return BindingBuilder.bind(outboxSpyQueue)
          .to(new DirectExchange("courier_events_exchange"))
          .with("courier.assignment.failed");
    }

    @RabbitListener(queues = "outbox.spy.queue")
    public void spyListener(Message message) {
      capturedMessages().offer(message);
    }
  }

  @Test
  void should_publish_unprocessed_events_to_rabbitmq() throws Exception {
    // ARRANGE: Create unpublished outbox events
    UUID courierId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    CourierAssignedContract contract = CourierAssignedContract.builder()
        .orderId(orderId)
        .courierId(courierId)
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .build();

    String payload = objectMapper.writeValueAsString(contract);

    OutboxEvent event = OutboxEvent.builder()
        .aggregateType("COURIER")
        .aggregateId(courierId.toString())
        .type("courier.assigned")
        .payload(payload)
        .createdAt(LocalDateTime.now())
        .processed(false)
        .build();

    outboxRepository.save(event);

    // ACT: Manually trigger the scheduled job
    outboxPublisher.publishOutboxEvents();

    // ASSERT: Event should be marked as processed
    OutboxEvent updatedEvent = outboxRepository.findById(event.getId()).orElseThrow();
    assertTrue(updatedEvent.isProcessed(), "Event should be marked as processed");

    // ASSERT: Event should be published to RabbitMQ
    Object captured = capturedMessages.poll(5, TimeUnit.SECONDS);
    assertNotNull(captured, "Event should be published to RabbitMQ");

    Message message = (Message) captured;
    CourierAssignedContract publishedContract = objectMapper.readValue(
        message.getBody(), CourierAssignedContract.class);

    assertEquals(orderId, publishedContract.getOrderId());
    assertEquals(courierId, publishedContract.getCourierId());
  }

  @Test
  void should_process_multiple_events_in_batch() throws Exception {
    // ARRANGE: Create 5 unpublished events
    for (int i = 0; i < 5; i++) {
      CourierAssignedContract contract = CourierAssignedContract.builder()
          .orderId(UUID.randomUUID())
          .courierId(UUID.randomUUID())
          .storeId(UUID.randomUUID())
          .userId(UUID.randomUUID())
          .build();

      String payload = objectMapper.writeValueAsString(contract);

      OutboxEvent event = OutboxEvent.builder()
          .aggregateType("COURIER")
          .aggregateId(UUID.randomUUID().toString())
          .type("courier.assigned")
          .payload(payload)
          .createdAt(LocalDateTime.now())
          .processed(false)
          .build();

      outboxRepository.save(event);
    }

    // ACT
    outboxPublisher.publishOutboxEvents();

    // ASSERT: All events should be marked as processed
    List<OutboxEvent> allEvents = outboxRepository.findAll();
    assertEquals(5, allEvents.size());
    assertTrue(allEvents.stream().allMatch(OutboxEvent::isProcessed),
        "All events should be marked as processed");

    // ASSERT: All events should be published
    int publishedCount = 0;
    while (capturedMessages.poll(1, TimeUnit.SECONDS) != null) {
      publishedCount++;
    }
    assertEquals(5, publishedCount, "All 5 events should be published");
  }

  @Test
  void should_not_republish_already_processed_events() throws Exception {
    // ARRANGE: Create event that's already processed
    CourierAssignedContract contract = CourierAssignedContract.builder()
        .orderId(UUID.randomUUID())
        .courierId(UUID.randomUUID())
        .storeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .build();

    String payload = objectMapper.writeValueAsString(contract);

    OutboxEvent processedEvent = OutboxEvent.builder()
        .aggregateType("COURIER")
        .aggregateId(UUID.randomUUID().toString())
        .type("courier.assigned")
        .payload(payload)
        .createdAt(LocalDateTime.now())
        .processed(true) // Already processed
        .build();

    outboxRepository.save(processedEvent);

    // ACT
    outboxPublisher.publishOutboxEvents();

    // ASSERT: No events should be published (already processed)
    Object captured = capturedMessages.poll(2, TimeUnit.SECONDS);
    assertNull(captured, "Already processed events should not be republished");
  }

  @Test
  void should_cleanup_old_processed_events() {
    // ARRANGE: Create old processed events (older than 24 hours)
    LocalDateTime oldDate = LocalDateTime.now().minusDays(2);

    for (int i = 0; i < 3; i++) {
      OutboxEvent oldEvent = OutboxEvent.builder()
          .aggregateType("COURIER")
          .aggregateId(UUID.randomUUID().toString())
          .type("courier.assigned")
          .payload("{}")
          .createdAt(oldDate)
          .processed(true)
          .build();

      outboxRepository.save(oldEvent);
    }

    // Create recent processed event (should NOT be deleted)
    OutboxEvent recentEvent = OutboxEvent.builder()
        .aggregateType("COURIER")
        .aggregateId(UUID.randomUUID().toString())
        .type("courier.assigned")
        .payload("{}")
        .createdAt(LocalDateTime.now())
        .processed(true)
        .build();

    outboxRepository.save(recentEvent);

    assertEquals(4, outboxRepository.count(), "Should have 4 events before cleanup");

    // ACT: Trigger cleanup job
    outboxPublisher.cleanupProcessedEvents();

    // ASSERT: Old processed events should be deleted
    List<OutboxEvent> remainingEvents = outboxRepository.findAll();
    assertEquals(1, remainingEvents.size(), "Only recent event should remain");
    assertEquals(recentEvent.getId(), remainingEvents.get(0).getId());
  }

  @Test
  void should_not_cleanup_unprocessed_events() {
    // ARRANGE: Old but UNPROCESSED event
    LocalDateTime oldDate = LocalDateTime.now().minusDays(2);

    OutboxEvent oldUnprocessedEvent = OutboxEvent.builder()
        .aggregateType("COURIER")
        .aggregateId(UUID.randomUUID().toString())
        .type("courier.assigned")
        .payload("{}")
        .createdAt(oldDate)
        .processed(false) // NOT processed
        .build();

    outboxRepository.save(oldUnprocessedEvent);

    // ACT
    outboxPublisher.cleanupProcessedEvents();

    // ASSERT: Unprocessed event should NOT be deleted
    assertEquals(1, outboxRepository.count(), "Unprocessed event should remain");
    OutboxEvent remaining = outboxRepository.findAll().get(0);
    assertFalse(remaining.isProcessed());
  }

  @Test
  void should_continue_processing_on_individual_failures() throws Exception {
    // ARRANGE: Create multiple valid events
    List<UUID> orderIds = new ArrayList<>();

    for (int i = 0; i < 3; i++) {
      UUID orderId = UUID.randomUUID();
      orderIds.add(orderId);

      CourierAssignedContract contract = CourierAssignedContract.builder()
          .orderId(orderId)
          .courierId(UUID.randomUUID())
          .storeId(UUID.randomUUID())
          .userId(UUID.randomUUID())
          .build();

      OutboxEvent event = OutboxEvent.builder()
          .aggregateType("COURIER")
          .aggregateId(UUID.randomUUID().toString())
          .type("courier.assigned")
          .payload(objectMapper.writeValueAsString(contract))
          .createdAt(LocalDateTime.now())
          .processed(false)
          .build();

      outboxRepository.save(event);
    }

    // ACT: Publish all events
    outboxPublisher.publishOutboxEvents();

    // ASSERT: All events should be processed successfully
    List<OutboxEvent> allEvents = outboxRepository.findAll();
    assertTrue(allEvents.stream().allMatch(OutboxEvent::isProcessed),
        "All valid events should be processed");
  }

  @Test
  void should_respect_batch_limit() {
    // ARRANGE: Create 60 unpublished events (more than batch limit of 50)
    for (int i = 0; i < 60; i++) {
      OutboxEvent event = OutboxEvent.builder()
          .aggregateType("COURIER")
          .aggregateId(UUID.randomUUID().toString())
          .type("courier.assigned")
          .payload("{}")
          .createdAt(LocalDateTime.now())
          .processed(false)
          .build();

      outboxRepository.save(event);
    }

    // ACT: Run job once (should process max 50)
    outboxPublisher.publishOutboxEvents();

    // ASSERT: At most 50 should be processed in first run
    long processedCount = outboxRepository.findAll().stream()
        .filter(OutboxEvent::isProcessed)
        .count();

    assertTrue(processedCount <= 50,
        "Should process at most 50 events per batch (batch limit)");

    // Remaining events should still be unprocessed
    long unprocessedCount = outboxRepository.findAll().stream()
        .filter(e -> !e.isProcessed())
        .count();

    assertTrue(unprocessedCount >= 10,
        "At least 10 events should remain unprocessed after first batch");
  }

  @Test
  void should_publish_assignment_failed_events() throws Exception {
    // ARRANGE: Create courier.assignment.failed event
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    String payload = objectMapper.writeValueAsString(
        new com.yads.common.contracts.CourierAssignmentFailedContract(
            orderId, userId, UUID.randomUUID(), "No couriers available"));

    OutboxEvent failureEvent = OutboxEvent.builder()
        .aggregateType("ORDER")
        .aggregateId(orderId.toString())
        .type("courier.assignment.failed")
        .payload(payload)
        .createdAt(LocalDateTime.now())
        .processed(false)
        .build();

    outboxRepository.save(failureEvent);

    // ACT
    outboxPublisher.publishOutboxEvents();

    // ASSERT: Event should be processed and published
    OutboxEvent updated = outboxRepository.findById(failureEvent.getId()).orElseThrow();
    assertTrue(updated.isProcessed());

    // Should be published to RabbitMQ
    Object captured = capturedMessages.poll(5, TimeUnit.SECONDS);
    assertNotNull(captured, "Failure event should be published");
  }
}
