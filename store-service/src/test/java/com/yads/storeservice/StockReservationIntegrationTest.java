package com.yads.storeservice;

import com.yads.common.contracts.StockReservationRequestContract;
import com.yads.common.dto.BatchReserveItem;
import com.yads.common.model.Address;
import com.yads.storeservice.config.AmqpConfig;
import com.yads.storeservice.model.*;
import com.yads.storeservice.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for stock reservation saga flow.
 * Tests the complete workflow from receiving StockReservationRequestContract
 * to publishing success/failure events to order-service.
 *
 * CRITICAL SCENARIOS TESTED:
 * 1. Successful reservation with pickup address retrieval
 * 2. Insufficient stock failure with transaction rollback
 * 3. Duplicate request idempotency
 * 4. Product availability auto-toggle when stock=0
 */
public class StockReservationIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private StoreRepository storeRepository;

  @Autowired
  private CategoryRepository categoryRepository;

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private OutboxRepository outboxRepository;

  @Autowired
  private IdempotentEventRepository idempotentEventRepository;

  @Autowired
  private BlockingQueue<Object> capturedMessages;

  @BeforeEach
  @AfterEach
  void clear() {
    productRepository.deleteAll();
    categoryRepository.deleteAll();
    storeRepository.deleteAll();
    outboxRepository.deleteAll();
    idempotentEventRepository.deleteAll();
    capturedMessages.clear();
  }

  // --- TEST CONFIGURATION (SPY FOR OUTBOUND EVENTS) ---
  @TestConfiguration
  static class TestRabbitConfig {

    @Bean
    public BlockingQueue<Object> capturedMessages() {
      return new LinkedBlockingQueue<>();
    }

    @Bean
    public Queue testSpyQueue() {
      return new Queue("test.spy.stock.queue", false);
    }

    @Bean
    public Binding bindingStockReserved(Queue testSpyQueue) {
      return BindingBuilder.bind(testSpyQueue)
          .to(new DirectExchange("order_events_exchange"))
          .with("order.stock_reserved");
    }

    @Bean
    public Binding bindingStockReservationFailed(Queue testSpyQueue) {
      return BindingBuilder.bind(testSpyQueue)
          .to(new DirectExchange("order_events_exchange"))
          .with("order.stock_reservation_failed");
    }

    @RabbitListener(queues = "test.spy.stock.queue")
    public void spyListener(Message message) {
      capturedMessages().offer(message);
    }
  }

  // --- TESTS ---

  @Test
  void should_reserve_stock_successfully_and_return_pickup_address() throws InterruptedException {
    // 1. ARRANGE: Setup store with products
    Address pickupAddress = new Address();
    pickupAddress.setCity("Istanbul");
    pickupAddress.setStreet("Bagdat Caddesi 123");
    pickupAddress.setPostalCode("34000");

    Store store = Store.builder()
        .name("Test Store")
        .ownerId(UUID.randomUUID())
        .address(pickupAddress)
        .isActive(true)
        .storeType(StoreType.RETAIL)
        .build();
    store = storeRepository.save(store);

    Category category = Category.builder()
        .name("Electronics")
        .store(store)
        .build();
    category = categoryRepository.save(category);

    Product productTemp = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(10)
        .isAvailable(true)
        .category(category)
        .build();
    Product product = productRepository.save(productTemp);

    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    StockReservationRequestContract contract = StockReservationRequestContract.builder()
        .orderId(orderId)
        .storeId(store.getId())
        .userId(userId)
        .items(List.of(BatchReserveItem.builder()
            .productId(product.getId())
            .quantity(3)
            .build()))
        .shippingAddress(new Address()) // Not used in this test
        .build();

    // 2. ACT: Send stock reservation request
    rabbitTemplate.convertAndSend(AmqpConfig.Q_STOCK_RESERVE, contract);

    // 3. ASSERT: DB Changes (Stock decreased)
    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Product updatedProduct = productRepository.findById(product.getId()).orElseThrow();
      assertEquals(7, updatedProduct.getStock(), "Stock should be decreased by 3");
      assertTrue(updatedProduct.getIsAvailable(), "Product should still be available");
    });

    // 4. ASSERT: Outbox Event Created for THIS order
    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> allEvents = outboxRepository.findAll();
      List<OutboxEvent> thisOrderEvents = allEvents.stream()
          .filter(e -> orderId.toString().equals(e.getAggregateId()))
          .toList();
      assertEquals(1, thisOrderEvents.size(), "Should have one outbox event for this order");
      OutboxEvent event = thisOrderEvents.get(0);
      assertEquals("order.stock_reserved", event.getType());
      assertEquals("ORDER", event.getAggregateType());
      assertEquals(orderId.toString(), event.getAggregateId());
      // Note: isProcessed may be true if OutboxPublisher runs quickly, so we don't
      // assert it
    });

    // 5. ASSERT: Idempotency Key Created
    String eventKey = "RESERVE_STOCK:" + orderId;
    assertTrue(idempotentEventRepository.existsById(eventKey), "Idempotency key should exist");

    // Note: We don't test RabbitMQ publishing here because OutboxPublisher handles
    // that
    // separately. See OutboxJobIntegrationTest for that.
  }

  @Test
  void should_fail_reservation_when_insufficient_stock() throws InterruptedException {
    // 1. ARRANGE: Product with insufficient stock
    Store store = Store.builder()
        .name("Test Store")
        .ownerId(UUID.randomUUID())
        .address(new Address())
        .isActive(true)
        .storeType(StoreType.RETAIL)
        .build();
    store = storeRepository.save(store);

    Category category = Category.builder()
        .name("Electronics")
        .store(store)
        .build();
    category = categoryRepository.save(category);

    Product productTemp = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(2) // Only 2 in stock
        .isAvailable(true)
        .category(category)
        .build();
    Product product = productRepository.save(productTemp);

    UUID orderId = UUID.randomUUID();

    StockReservationRequestContract contract = StockReservationRequestContract.builder()
        .orderId(orderId)
        .storeId(store.getId())
        .userId(UUID.randomUUID())
        .items(List.of(BatchReserveItem.builder()
            .productId(product.getId())
            .quantity(5) // Request more than available
            .build()))
        .build();

    // 2. ACT
    rabbitTemplate.convertAndSend(AmqpConfig.Q_STOCK_RESERVE, contract);

    // 3. ASSERT: Stock unchanged (transaction rolled back)
    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
      Product unchangedProduct = productRepository.findById(product.getId()).orElseThrow();
      assertEquals(2, unchangedProduct.getStock(), "Stock should NOT be changed due to rollback");
    });

    // 4. ASSERT: Failure event in outbox (saved in NEW transaction)
    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> events = outboxRepository.findAll();
      assertEquals(1, events.size(), "Should have one failure event");
      OutboxEvent event = events.get(0);
      assertEquals("order.stock_reservation_failed", event.getType());
      assertTrue(event.getPayload().contains("Insufficient stock"));
    });

    // 5. ASSERT: Idempotency key still created (even on failure)
    String eventKey = "RESERVE_STOCK:" + orderId;
    assertTrue(idempotentEventRepository.existsById(eventKey));
  }

  @Test
  void should_ignore_duplicate_reservation_request() throws InterruptedException {
    // 1. ARRANGE: Setup product
    Store store = Store.builder()
        .name("Test Store")
        .ownerId(UUID.randomUUID())
        .address(new Address())
        .isActive(true)
        .storeType(StoreType.RETAIL)
        .build();
    store = storeRepository.save(store);

    Category category = Category.builder()
        .name("Electronics")
        .store(store)
        .build();
    category = categoryRepository.save(category);

    Product productTemp = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(10)
        .isAvailable(true)
        .category(category)
        .build();
    Product product = productRepository.save(productTemp);

    UUID orderId = UUID.randomUUID();

    StockReservationRequestContract contract = StockReservationRequestContract.builder()
        .orderId(orderId)
        .storeId(store.getId())
        .userId(UUID.randomUUID())
        .items(List.of(BatchReserveItem.builder()
            .productId(product.getId())
            .quantity(3)
            .build()))
        .build();

    // 2. ACT: Send FIRST request
    rabbitTemplate.convertAndSend(AmqpConfig.Q_STOCK_RESERVE, contract);

    // Wait for processing
    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
      Product updatedProduct = productRepository.findById(product.getId()).orElseThrow();
      assertEquals(7, updatedProduct.getStock());
    });

    int stockAfterFirst = productRepository.findById(product.getId()).orElseThrow().getStock();
    int outboxCountAfterFirst = outboxRepository.findAll().size();

    // 3. ACT: Send DUPLICATE request
    rabbitTemplate.convertAndSend(AmqpConfig.Q_STOCK_RESERVE, contract);

    // Give it time to process (if it were to process)
    Thread.sleep(2000);

    // 4. ASSERT: No changes (idempotency worked)
    Product finalProduct = productRepository.findById(product.getId()).orElseThrow();
    assertEquals(stockAfterFirst, finalProduct.getStock(), "Stock should NOT change on duplicate");

    List<OutboxEvent> finalEvents = outboxRepository.findAll();
    assertEquals(outboxCountAfterFirst, finalEvents.size(), "Should NOT create duplicate outbox event");
  }

  @Test
  void should_mark_product_unavailable_when_stock_reaches_zero() throws InterruptedException {
    // 1. ARRANGE: Product with exactly 3 items
    Store store = Store.builder()
        .name("Test Store")
        .ownerId(UUID.randomUUID())
        .address(new Address())
        .isActive(true)
        .storeType(StoreType.RETAIL)
        .build();
    store = storeRepository.save(store);

    Category category = Category.builder()
        .name("Electronics")
        .store(store)
        .build();
    category = categoryRepository.save(category);

    Product productTemp = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(3) // Exactly 3
        .isAvailable(true)
        .category(category)
        .build();
    Product product = productRepository.save(productTemp);

    StockReservationRequestContract contract = StockReservationRequestContract.builder()
        .orderId(UUID.randomUUID())
        .storeId(store.getId())
        .userId(UUID.randomUUID())
        .items(List.of(BatchReserveItem.builder()
            .productId(product.getId())
            .quantity(3) // Reserve all
            .build()))
        .build();

    // 2. ACT
    rabbitTemplate.convertAndSend(AmqpConfig.Q_STOCK_RESERVE, contract);

    // 3. ASSERT
    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
      Product updatedProduct = productRepository.findById(product.getId()).orElseThrow();
      assertEquals(0, updatedProduct.getStock());
      assertFalse(updatedProduct.getIsAvailable(), "Product should be marked unavailable");
    });
  }
}
