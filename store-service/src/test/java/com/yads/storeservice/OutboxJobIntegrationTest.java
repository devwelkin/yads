package com.yads.storeservice;

import com.yads.storeservice.job.OutboxPublisher;
import com.yads.storeservice.model.OutboxEvent;
import com.yads.storeservice.repository.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for OutboxPublisher scheduled job.
 * Tests both event publishing and cleanup functionality.
 *
 * CRITICAL PATTERN: Transactional Outbox Pattern
 * - Events saved to DB in same transaction as business logic
 * - Background job publishes events to RabbitMQ
 * - Ensures at-least-once delivery even if RabbitMQ is down during business
 * transaction
 * - Cleanup job removes old processed events to prevent table bloat
 */
public class OutboxJobIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private OutboxPublisher outboxPublisher;

  @Autowired
  private OutboxRepository outboxRepository;

  @Autowired
  private BlockingQueue<Object> capturedMessages;

  @BeforeEach
  @AfterEach
  void clear() {
    outboxRepository.deleteAll();
    capturedMessages.clear();
  }

  // --- SPY CONFIG ---
  @TestConfiguration
  static class OutboxSpyConfig {

    @Bean
    public BlockingQueue<Object> capturedMessages() {
      return new LinkedBlockingQueue<>();
    }

    @Bean
    public Queue spyQueue() {
      return new Queue("spy.outbox.queue", false);
    }

    @Bean
    public Binding bindingStockReserved(Queue spyQueue) {
      return BindingBuilder.bind(spyQueue)
          .to(new DirectExchange("order_events_exchange"))
          .with("order.stock_reserved");
    }

    @Bean
    public Binding bindingProductUpdated(Queue spyQueue) {
      return BindingBuilder.bind(spyQueue)
          .to(new DirectExchange("order_events_exchange"))
          .with("product.updated");
    }

    @Bean
    public SpyListener spyListener(BlockingQueue<Object> capturedMessages) {
      return new SpyListener(capturedMessages);
    }

    static class SpyListener {
      private final BlockingQueue<Object> queue;

      public SpyListener(BlockingQueue<Object> queue) {
        this.queue = queue;
      }

      @RabbitListener(queues = "spy.outbox.queue")
      public void onMessage(Object msg) {
        queue.offer(msg);
      }
    }
  }

  @Test
  void should_publish_pending_events_and_mark_processed() throws InterruptedException {
    // 1. ARRANGE: Create unprocessed outbox event
    OutboxEvent event = OutboxEvent.builder()
        .aggregateType("ORDER")
        .aggregateId(UUID.randomUUID().toString())
        .type("order.stock_reserved")
        .payload("{\"orderId\": \"123\", \"storeId\": \"456\"}")
        .createdAt(LocalDateTime.now())
        .processed(false)
        .build();
    outboxRepository.save(event);

    // 2. ACT: Manually trigger the scheduled job
    outboxPublisher.publishOutboxEvents();

    // 3. ASSERT: Event marked as processed in DB
    OutboxEvent processedEvent = outboxRepository.findById(event.getId()).orElseThrow();
    assertTrue(processedEvent.isProcessed(), "Event should be marked as processed");

    // 4. ASSERT: Message actually sent to RabbitMQ
    Object captured = capturedMessages.poll(3, TimeUnit.SECONDS);
    assertNotNull(captured, "Message should be published to RabbitMQ");
  }

  @Test
  void should_handle_multiple_events_in_batch() {
    // 1. ARRANGE: Create 10 unprocessed events
    for (int i = 0; i < 10; i++) {
      OutboxEvent event = OutboxEvent.builder()
          .aggregateType("PRODUCT")
          .aggregateId(UUID.randomUUID().toString())
          .type("product.updated")
          .payload("{\"productId\": \"" + i + "\"}")
          .createdAt(LocalDateTime.now())
          .processed(false)
          .build();
      outboxRepository.save(event);
    }

    // 2. ACT
    outboxPublisher.publishOutboxEvents();

    // 3. ASSERT: All marked as processed
    long processedCount = outboxRepository.findAll().stream()
        .filter(OutboxEvent::isProcessed)
        .count();
    assertEquals(10, processedCount, "All 10 events should be processed");
  }

  @Test
  void should_not_reprocess_already_processed_events() {
    // 1. ARRANGE: Create already processed event
    OutboxEvent processedEvent = OutboxEvent.builder()
        .aggregateType("ORDER")
        .aggregateId(UUID.randomUUID().toString())
        .type("order.stock_reserved")
        .payload("{\"orderId\": \"123\"}")
        .createdAt(LocalDateTime.now())
        .processed(true) // Already processed
        .build();
    outboxRepository.save(processedEvent);

    capturedMessages.clear();

    // 2. ACT
    outboxPublisher.publishOutboxEvents();

    // 3. ASSERT: No new messages sent
    try {
      Object captured = capturedMessages.poll(2, TimeUnit.SECONDS);
      assertNull(captured, "Should not republish already processed events");
    } catch (InterruptedException e) {
      fail("Interrupted");
    }
  }

  @Test
  void should_cleanup_old_processed_events() {
    // 1. ARRANGE: Create old processed events
    LocalDateTime twoDaysAgo = LocalDateTime.now().minusDays(2);

    for (int i = 0; i < 5; i++) {
      OutboxEvent oldEvent = OutboxEvent.builder()
          .aggregateType("ORDER")
          .aggregateId(UUID.randomUUID().toString())
          .type("order.stock_reserved")
          .payload("{\"orderId\": \"" + i + "\"}")
          .createdAt(twoDaysAgo)
          .processed(true)
          .build();
      outboxRepository.save(oldEvent);
    }

    // Create recent processed event (should NOT be deleted)
    OutboxEvent recentEvent = OutboxEvent.builder()
        .aggregateType("ORDER")
        .aggregateId(UUID.randomUUID().toString())
        .type("order.stock_reserved")
        .payload("{\"orderId\": \"recent\"}")
        .createdAt(LocalDateTime.now().minusHours(12))
        .processed(true)
        .build();
    outboxRepository.save(recentEvent);

    assertEquals(6, outboxRepository.count(), "Should have 6 events before cleanup");

    // 2. ACT: Run cleanup job
    outboxPublisher.cleanupProcessedEvents();

    // 3. ASSERT: Old events deleted, recent kept
    assertEquals(1, outboxRepository.count(), "Should have 1 event after cleanup (recent one)");

    OutboxEvent remaining = outboxRepository.findAll().get(0);
    assertTrue(remaining.getPayload().contains("recent"), "Recent event should remain");
  }

  @Test
  void should_handle_large_batch_cleanup() {
    // 1. ARRANGE: Create 2500 old processed events (more than cleanup batch size of
    // 1000)
    LocalDateTime twoDaysAgo = LocalDateTime.now().minusDays(2);

    for (int i = 0; i < 2500; i++) {
      OutboxEvent oldEvent = OutboxEvent.builder()
          .aggregateType("PRODUCT")
          .aggregateId(UUID.randomUUID().toString())
          .type("product.updated")
          .payload("{\"productId\": \"" + i + "\"}")
          .createdAt(twoDaysAgo)
          .processed(true)
          .build();
      outboxRepository.save(oldEvent);
    }

    assertEquals(2500, outboxRepository.count());

    // 2. ACT
    outboxPublisher.cleanupProcessedEvents();

    // 3. ASSERT: All old events deleted
    assertEquals(0, outboxRepository.count(), "All old events should be deleted");
  }

  /**
   * NOTE: This test is disabled because PostgreSQL JSONB column type
   * rejects invalid JSON at insert time, making it impossible to test
   * error handling for malformed payloads. In production, payload validation
   * should occur before persisting to outbox table.
   */
  @Test
  @Disabled("PostgreSQL JSONB validation prevents inserting invalid JSON")
  void should_continue_processing_if_one_event_fails() {
    // 1. ARRANGE: Create valid and invalid events
    OutboxEvent validEvent = OutboxEvent.builder()
        .aggregateType("ORDER")
        .aggregateId(UUID.randomUUID().toString())
        .type("order.stock_reserved")
        .payload("{\"orderId\": \"valid\"}")
        .createdAt(LocalDateTime.now())
        .processed(false)
        .build();
    outboxRepository.save(validEvent);

    OutboxEvent invalidEvent = OutboxEvent.builder()
        .aggregateType("ORDER")
        .aggregateId(UUID.randomUUID().toString())
        .type("order.stock_reserved")
        .payload("INVALID JSON {{{") // Malformed JSON
        .createdAt(LocalDateTime.now())
        .processed(false)
        .build();
    outboxRepository.save(invalidEvent);

    OutboxEvent anotherValidEvent = OutboxEvent.builder()
        .aggregateType("ORDER")
        .aggregateId(UUID.randomUUID().toString())
        .type("order.stock_reserved")
        .payload("{\"orderId\": \"another_valid\"}")
        .createdAt(LocalDateTime.now())
        .processed(false)
        .build();
    outboxRepository.save(anotherValidEvent);

    // 2. ACT
    outboxPublisher.publishOutboxEvents();

    // 3. ASSERT: Valid events processed, invalid one remains
    OutboxEvent valid1 = outboxRepository.findById(validEvent.getId()).orElseThrow();
    assertTrue(valid1.isProcessed(), "First valid event should be processed");

    OutboxEvent valid2 = outboxRepository.findById(anotherValidEvent.getId()).orElseThrow();
    assertTrue(valid2.isProcessed(), "Second valid event should be processed");

    OutboxEvent invalid = outboxRepository.findById(invalidEvent.getId()).orElseThrow();
    assertFalse(invalid.isProcessed(), "Invalid event should NOT be processed (error handling)");
  }
}
