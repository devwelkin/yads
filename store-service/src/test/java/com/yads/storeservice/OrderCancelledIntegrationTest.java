package com.yads.storeservice;

import com.yads.common.contracts.OrderCancelledContract;
import com.yads.common.dto.BatchReserveItem;
import com.yads.storeservice.model.Category;
import com.yads.storeservice.model.Product;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.model.StoreType;
import com.yads.storeservice.repository.CategoryRepository;
import com.yads.storeservice.repository.IdempotentEventRepository;
import com.yads.storeservice.repository.ProductRepository;
import com.yads.storeservice.repository.StoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for order cancellation and stock restoration flow.
 * Tests the OrderCancelledSubscriber logic including ghost inventory
 * prevention.
 *
 * CRITICAL CONCEPT: Ghost Inventory Prevention
 * When an order is cancelled, we must check oldStatus before restoring stock:
 * - PENDING -> Stock was NEVER deducted -> NO restoration (would create phantom
 * stock)
 * - RESERVING_STOCK -> Stock was NEVER deducted -> NO restoration
 * - PREPARING -> Stock WAS deducted -> YES, restore stock
 * - ON_THE_WAY -> Stock WAS deducted -> YES, restore stock
 * - DELIVERED -> Too late, can't cancel -> Should not receive this event
 *
 * This test suite validates this critical business logic.
 */
public class OrderCancelledIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private StoreRepository storeRepository;

  @Autowired
  private CategoryRepository categoryRepository;

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private IdempotentEventRepository idempotentEventRepository;

  @Autowired
  private TestProductService testProductService;

  private Store store;
  private Category category;
  private Product product;

  @BeforeEach
  void setup() {
    store = Store.builder()
        .name("Test Store")
        .ownerId(UUID.randomUUID())
        .isActive(true)
        .storeType(StoreType.RETAIL)
        .build();
    store = storeRepository.save(store);

    category = Category.builder()
        .name("Electronics")
        .store(store)
        .build();
    category = categoryRepository.save(category);

    product = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(5) // Initial stock: 5
        .isAvailable(true)
        .category(category)
        .build();
    product = productRepository.save(product);
  }

  @AfterEach
  void clear() {
    productRepository.deleteAll();
    categoryRepository.deleteAll();
    storeRepository.deleteAll();
    idempotentEventRepository.deleteAll();
  }

  @Test
  void should_restore_stock_when_order_cancelled_from_preparing_status() {
    // 1. ARRANGE: Simulate order was PREPARING (stock already deducted to 2)
    testProductService.decreaseStock(product.getId(), 3); // 5 - 3 = 2

    Product afterReservation = productRepository.findById(product.getId()).orElseThrow();
    assertEquals(2, afterReservation.getStock());

    UUID orderId = UUID.randomUUID();
    OrderCancelledContract contract = OrderCancelledContract.builder()
        .orderId(orderId)
        .storeId(store.getId())
        .userId(UUID.randomUUID())
        .oldStatus("PREPARING") // Stock WAS deducted
        .items(List.of(BatchReserveItem.builder()
            .productId(product.getId())
            .quantity(3)
            .build()))
        .build();

    // 2. ACT: Send cancellation event
    rabbitTemplate.convertAndSend("order_cancelled_stock_restore_queue", contract);

    // 3. ASSERT: Stock restored
    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
      Product restoredProduct = productRepository.findById(product.getId()).orElseThrow();
      assertEquals(5, restoredProduct.getStock(), "Stock should be restored to original 5");
      assertTrue(restoredProduct.getIsAvailable());
    });

    // 4. ASSERT: Idempotency key created
    String eventKey = "CANCEL_ORDER:" + orderId;
    assertTrue(idempotentEventRepository.existsById(eventKey));
  }

  @Test
  void should_restore_stock_when_order_cancelled_from_on_the_way_status() {
    // 1. ARRANGE: Simulate order was ON_THE_WAY
    testProductService.decreaseStock(product.getId(), 2); // 5 - 2 = 3

    UUID orderId = UUID.randomUUID();
    OrderCancelledContract contract = OrderCancelledContract.builder()
        .orderId(orderId)
        .storeId(store.getId())
        .userId(UUID.randomUUID())
        .oldStatus("ON_THE_WAY") // Stock WAS deducted
        .items(List.of(BatchReserveItem.builder()
            .productId(product.getId())
            .quantity(2)
            .build()))
        .build();

    // 2. ACT
    rabbitTemplate.convertAndSend("order_cancelled_stock_restore_queue", contract);

    // 3. ASSERT
    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
      Product restoredProduct = productRepository.findById(product.getId()).orElseThrow();
      assertEquals(5, restoredProduct.getStock());
    });
  }

  @Test
  void should_NOT_restore_stock_when_order_cancelled_from_pending_status() {
    // 1. ARRANGE: Order was PENDING (stock was NEVER deducted)
    int initialStock = productRepository.findById(product.getId()).orElseThrow().getStock();
    assertEquals(5, initialStock);

    UUID orderId = UUID.randomUUID();
    OrderCancelledContract contract = OrderCancelledContract.builder()
        .orderId(orderId)
        .storeId(store.getId())
        .userId(UUID.randomUUID())
        .oldStatus("PENDING") // CRITICAL: Stock was NEVER deducted!
        .items(List.of(BatchReserveItem.builder()
            .productId(product.getId())
            .quantity(3)
            .build()))
        .build();

    // 2. ACT
    rabbitTemplate.convertAndSend("order_cancelled_stock_restore_queue", contract);

    // Give time to process (if it were to process incorrectly)
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      fail();
    }

    // 3. ASSERT: Stock UNCHANGED (ghost inventory prevented!)
    Product unchangedProduct = productRepository.findById(product.getId()).orElseThrow();
    assertEquals(5, unchangedProduct.getStock(),
        "Stock should NOT change - prevents GHOST INVENTORY");

    // 4. ASSERT: Idempotency key still created (but no stock change)
    String eventKey = "CANCEL_ORDER:" + orderId;
    assertTrue(idempotentEventRepository.existsById(eventKey));
  }

  @Test
  void should_ignore_duplicate_cancellation_events() {
    // 1. ARRANGE: First cancellation
    testProductService.decreaseStock(product.getId(), 3); // 5 - 3 = 2

    UUID orderId = UUID.randomUUID();
    OrderCancelledContract contract = OrderCancelledContract.builder()
        .orderId(orderId)
        .storeId(store.getId())
        .userId(UUID.randomUUID())
        .oldStatus("PREPARING")
        .items(List.of(BatchReserveItem.builder()
            .productId(product.getId())
            .quantity(3)
            .build()))
        .build();

    // 2. ACT: Send FIRST cancellation
    rabbitTemplate.convertAndSend("order_cancelled_stock_restore_queue", contract);

    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Product restored = productRepository.findById(product.getId()).orElseThrow();
      assertEquals(5, restored.getStock());
    });

    // 3. ACT: Send DUPLICATE cancellation
    rabbitTemplate.convertAndSend("order_cancelled_stock_restore_queue", contract);

    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      fail();
    }

    // 4. ASSERT: Stock NOT increased again (would be 8 if duplicate processed)
    Product finalProduct = productRepository.findById(product.getId()).orElseThrow();
    assertEquals(5, finalProduct.getStock(), "Stock should remain 5, not double-restored");
  }

  @Test
  void should_mark_product_available_when_stock_restored_above_zero() {
    // 1. ARRANGE: Product with 0 stock (unavailable)
    testProductService.decreaseStock(product.getId(), 5); // 5 - 5 = 0

    Product unavailableProduct = productRepository.findById(product.getId()).orElseThrow();
    assertEquals(0, unavailableProduct.getStock());
    assertFalse(unavailableProduct.getIsAvailable());

    UUID orderId = UUID.randomUUID();
    OrderCancelledContract contract = OrderCancelledContract.builder()
        .orderId(orderId)
        .storeId(store.getId())
        .userId(UUID.randomUUID())
        .oldStatus("PREPARING")
        .items(List.of(BatchReserveItem.builder()
            .productId(product.getId())
            .quantity(5) // Restore all 5
            .build()))
        .build();

    // 2. ACT
    rabbitTemplate.convertAndSend("order_cancelled_stock_restore_queue", contract);

    // 3. ASSERT: Product available again
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Product restoredProduct = productRepository.findById(product.getId()).orElseThrow();
      assertEquals(5, restoredProduct.getStock());
      assertTrue(restoredProduct.getIsAvailable(),
          "Product should be marked available when stock > 0");
    });
  }

  @Test
  void should_handle_multiple_products_in_single_order() {
    // 1. ARRANGE: Create second product
    Product productTemp = Product.builder()
        .name("Mouse")
        .price(BigDecimal.valueOf(50))
        .stock(10)
        .isAvailable(true)
        .category(category)
        .build();
    Product product2 = productRepository.save(productTemp);

    // Decrease stock for both products
    testProductService.decreaseStock(product.getId(), 2); // Laptop: 5 - 2 = 3
    testProductService.decreaseStock(product2.getId(), 3); // Mouse: 10 - 3 = 7

    UUID orderId = UUID.randomUUID();
    OrderCancelledContract contract = OrderCancelledContract.builder()
        .orderId(orderId)
        .storeId(store.getId())
        .userId(UUID.randomUUID())
        .oldStatus("PREPARING")
        .items(List.of(
            BatchReserveItem.builder()
                .productId(product.getId())
                .quantity(2)
                .build(),
            BatchReserveItem.builder()
                .productId(product2.getId())
                .quantity(3)
                .build()))
        .build();

    // 2. ACT
    rabbitTemplate.convertAndSend("order_cancelled_stock_restore_queue", contract);

    // 3. ASSERT: Both products restored
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      Product restored1 = productRepository.findById(product.getId()).orElseThrow();
      assertEquals(5, restored1.getStock());

      Product restored2 = productRepository.findById(product2.getId()).orElseThrow();
      assertEquals(10, restored2.getStock());
    });
  }
}
