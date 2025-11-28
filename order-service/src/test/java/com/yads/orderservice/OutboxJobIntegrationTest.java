package com.yads.orderservice;

import com.yads.orderservice.job.OutboxPublisher;
import com.yads.orderservice.model.OutboxEvent;
import com.yads.orderservice.repository.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class OutboxJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxPublisher outboxPublisher; // Test edecegimiz job bean'i

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

    // --- SPY CONFIG (Mesaj gercekten rabbit'e gidiyor mu?) ---
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
        public Binding binding(Queue spyQueue) {
            return BindingBuilder.bind(spyQueue)
                    .to(new DirectExchange("order_events_exchange"))
                    .with("order.created");
        }

        // decouple the listener so we don't need to inject the queue into the config instance
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
        // 1. ARRANGE
        // DB'ye islenmemis bir event koyalim
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("ORDER")
                .aggregateId("123")
                .type("order.created")
                .payload("{\"orderId\": \"123\", \"status\": \"PENDING\"}") // Basit JSON
                .createdAt(LocalDateTime.now())
                .processed(false)
                .build();
        outboxRepository.save(event);

        // 2. ACT
        // Scheduler'in calismasini beklemiyoruz, metodu elle tetikliyoruz
        outboxPublisher.publishOutboxEvents();

        // 3. ASSERT - RABBIT
        // Mesaj exchange'e gitmis mi?
        Object msg = capturedMessages.poll(5, TimeUnit.SECONDS);
        assertNotNull(msg, "Message should be published to RabbitMQ");
        // Not: msg burada LinkedHashMap olarak gelir (Jackson default), detayina bakmaya gerek yok.

        // 4. ASSERT - DB
        // Event processed=true olmus mu?
        var updatedEvent = outboxRepository.findById(event.getId()).orElseThrow();
        assertTrue(updatedEvent.isProcessed(), "Event should be marked as processed");
    }

    @Test
    void should_cleanup_old_processed_events() {
        // 1. ARRANGE
        // Eski ve islenmis (silinmesi gereken)
        OutboxEvent oldEvent = OutboxEvent.builder()
                .aggregateType("ORDER")
                .aggregateId("123")
                .type("test")
                .payload("{}")
                .createdAt(LocalDateTime.now().minusDays(2)) // 2 gun once
                .processed(true)
                .build();

        // Yeni ve islenmis (silinmemesi gereken)
        OutboxEvent newEvent = OutboxEvent.builder()
                .aggregateType("ORDER")
                .aggregateId("1232")
                .type("test")
                .payload("{}")
                .createdAt(LocalDateTime.now().minusHours(1))
                .processed(true)
                .build();

        // Eski ama islenmemis (silinmemesi gereken - hala kuyrukta olabilir)
        OutboxEvent pendingEvent = OutboxEvent.builder()
                .aggregateType("ORDER")
                .aggregateId("12213")
                .type("test")
                .payload("{}")
                .createdAt(LocalDateTime.now().minusDays(2))
                .processed(false)
                .build();

        outboxRepository.save(oldEvent);
        outboxRepository.save(newEvent);
        outboxRepository.save(pendingEvent);

        // 2. ACT
        outboxPublisher.cleanupProcessedEvents();

        // 3. ASSERT
        assertTrue(outboxRepository.findById(oldEvent.getId()).isEmpty(), "Old processed event should be deleted");
        assertTrue(outboxRepository.findById(newEvent.getId()).isPresent(), "Recent event should be kept");
        assertTrue(outboxRepository.findById(pendingEvent.getId()).isPresent(), "Pending event should be kept regardless of age");
    }
}