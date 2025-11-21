package com.yads.storeservice;

import com.yads.storeservice.dto.ProductRequest;
import com.yads.storeservice.model.Category;
import com.yads.storeservice.model.OutboxEvent;
import com.yads.storeservice.model.Product;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.model.StoreType;
import com.yads.storeservice.repository.CategoryRepository;
import com.yads.storeservice.repository.OutboxRepository;
import com.yads.storeservice.repository.ProductRepository;
import com.yads.storeservice.repository.StoreRepository;
import com.yads.storeservice.services.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ProductEventListener.
 * Tests that product lifecycle events (create, update, delete, stock changes)
 * are properly published to the outbox table via @TransactionalEventListener.
 *
 * CRITICAL PATTERN: @TransactionalEventListener(phase = AFTER_COMMIT)
 * - Events are only published AFTER the transaction commits successfully
 * - If transaction rolls back, NO events are published
 * - This ensures eventual consistency and prevents phantom events
 */
public class ProductEventIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private ProductService productService;

  @Autowired
  private StoreRepository storeRepository;

  @Autowired
  private CategoryRepository categoryRepository;

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private OutboxRepository outboxRepository;

  private Store store;
  private Category category;
  private UUID ownerId;

  @BeforeEach
  void setup() {
    ownerId = UUID.randomUUID();

    store = Store.builder()
        .name("Test Store")
        .ownerId(ownerId)
        .isActive(true)
        .storeType(StoreType.RETAIL)
        .build();
    store = storeRepository.save(store);

    category = Category.builder()
        .name("Electronics")
        .store(store)
        .build();
    category = categoryRepository.save(category);
  }

  @AfterEach
  void tearDown() {
    outboxRepository.deleteAll();
    productRepository.deleteAll();
    categoryRepository.deleteAll();
    storeRepository.deleteAll();
  }

  @Test
  void should_create_outbox_event_when_product_created() {
    // 1. ARRANGE
    ProductRequest request = ProductRequest.builder()
        .name("Laptop")
        .description("Gaming laptop")
        .price(BigDecimal.valueOf(1500))
        .stock(10)
        .build();

    // 2. ACT: Create product (triggers ProductUpdateEvent)
    productService.createProduct(category.getId(), request, ownerId);

    // 3. ASSERT: Outbox event created after transaction commit
    await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> events = outboxRepository.findAll();
      assertEquals(1, events.size(), "Should have one outbox event");

      OutboxEvent event = events.get(0);
      assertEquals("PRODUCT", event.getAggregateType());
      assertEquals("product.created", event.getType());
      assertFalse(event.isProcessed());
      assertTrue(event.getPayload().contains("Laptop"));
    });
  }

  @Test
  void should_create_outbox_event_when_product_updated() {
    // 1. ARRANGE: Create product first
    Product product = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(10)
        .isAvailable(true)
        .category(category)
        .build();
    product = productRepository.save(product);

    outboxRepository.deleteAll(); // Clear creation event

    // 2. ACT: Update product
    ProductRequest updateRequest = ProductRequest.builder()
        .name("Gaming Laptop")
        .description("Updated description")
        .price(BigDecimal.valueOf(1500))
        .stock(15)
        .build();

    productService.updateProduct(product.getId(), updateRequest, ownerId);

    // 3. ASSERT: Update event in outbox
    await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> events = outboxRepository.findAll();
      assertEquals(1, events.size());

      OutboxEvent event = events.get(0);
      assertEquals("product.updated", event.getType());
      assertTrue(event.getPayload().contains("Gaming Laptop"));
    });
  }

  @Test
  void should_create_outbox_event_when_product_deleted() {
    // 1. ARRANGE
    Product product = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(10)
        .isAvailable(true)
        .category(category)
        .build();
    product = productRepository.save(product);
    UUID productId = product.getId();

    outboxRepository.deleteAll();

    // 2. ACT: Delete product
    productService.deleteProduct(productId, ownerId);

    // 3. ASSERT: Deletion event in outbox
    await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> events = outboxRepository.findAll();
      assertEquals(1, events.size());

      OutboxEvent event = events.get(0);
      assertEquals("product.deleted", event.getType());
      assertEquals(productId.toString(), event.getAggregateId());
    });
  }

  @Test
  void should_create_outbox_event_when_stock_updated() {
    // 1. ARRANGE
    Product product = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(10)
        .isAvailable(true)
        .category(category)
        .build();
    product = productRepository.save(product);

    outboxRepository.deleteAll();

    // 2. ACT: Update stock
    productService.updateStock(product.getId(), 20, ownerId);

    // 3. ASSERT: Stock update event in outbox
    await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> events = outboxRepository.findAll();
      assertEquals(1, events.size());

      OutboxEvent event = events.get(0);
      assertEquals("product.stock.updated", event.getType());
    });
  }

  @Test
  void should_create_outbox_event_when_availability_toggled() {
    // 1. ARRANGE
    Product product = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(10)
        .isAvailable(true)
        .category(category)
        .build();
    product = productRepository.save(product);

    outboxRepository.deleteAll();

    // 2. ACT: Toggle availability
    productService.toggleAvailability(product.getId(), ownerId);

    // 3. ASSERT: Availability update event in outbox
    await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> events = outboxRepository.findAll();
      assertEquals(1, events.size());

      OutboxEvent event = events.get(0);
      assertEquals("product.availability.updated", event.getType());
    });
  }

  @Test
  void should_NOT_create_event_if_transaction_fails() {
    // 1. ARRANGE: Try to create product with non-existent category
    UUID nonExistentCategoryId = UUID.randomUUID();

    ProductRequest request = ProductRequest.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(10)
        .build();

    // 2. ACT & ASSERT: Operation fails
    assertThrows(Exception.class, () -> {
      productService.createProduct(nonExistentCategoryId, request, ownerId);
    });

    // 3. ASSERT: NO outbox event created (transaction rolled back)
    try {
      Thread.sleep(1000); // Give time if event were to be created
    } catch (InterruptedException e) {
      fail();
    }

    List<OutboxEvent> events = outboxRepository.findAll();
    assertEquals(0, events.size(), "Should NOT create event on transaction failure");
  }

  @Test
  void should_create_multiple_events_for_multiple_products() {
    // 1. ARRANGE & ACT: Create 3 products
    for (int i = 0; i < 3; i++) {
      ProductRequest request = ProductRequest.builder()
          .name("Product " + i)
          .price(BigDecimal.valueOf(100))
          .stock(5)
          .build();

      productService.createProduct(category.getId(), request, ownerId);
    }

    // 2. ASSERT: 3 outbox events created
    await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
      List<OutboxEvent> events = outboxRepository.findAll();
      assertEquals(3, events.size(), "Should have 3 events for 3 products");

      long creationEvents = events.stream()
          .filter(e -> "product.created".equals(e.getType()))
          .count();
      assertEquals(3, creationEvents);
    });
  }
}
