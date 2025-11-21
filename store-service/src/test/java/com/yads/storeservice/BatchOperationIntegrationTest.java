package com.yads.storeservice;

import com.yads.common.dto.BatchReserveItem;
import com.yads.common.dto.BatchReserveStockRequest;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for batch stock operations (reserve and restore).
 * Tests the CRITICAL atomicity guarantee: ALL or NOTHING.
 *
 * BUSINESS RULE: If ANY product in a batch fails, the ENTIRE batch rolls back.
 * This prevents partial order fulfillment, which would be a terrible user
 * experience:
 * - User orders 3 products
 * - Only 2 are reserved
 * - Order shows as "confirmed" but one item is missing
 * - Customer is angry!
 *
 * Solution: Transaction rollback ensures ALL products are reserved together,
 * or NONE are reserved.
 */
public class BatchOperationIntegrationTest extends AbstractIntegrationTest {

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
  }

  @AfterEach
  void tearDown() {
    outboxRepository.deleteAll();
    productRepository.deleteAll();
    categoryRepository.deleteAll();
    storeRepository.deleteAll();
  }

  @Test
  void should_rollback_entire_batch_if_one_product_has_insufficient_stock() {
    // 1. ARRANGE: Create 3 products with different stock levels
    Product productA = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(10) // Sufficient
        .isAvailable(true)
        .category(category)
        .build();
    productA = productRepository.save(productA);

    Product productB = Product.builder()
        .name("Mouse")
        .price(BigDecimal.valueOf(50))
        .stock(5) // Sufficient
        .isAvailable(true)
        .category(category)
        .build();
    productB = productRepository.save(productB);

    Product productC = Product.builder()
        .name("Keyboard")
        .price(BigDecimal.valueOf(100))
        .stock(2) // INSUFFICIENT (want 5 but only have 2)
        .isAvailable(true)
        .category(category)
        .build();
    productC = productRepository.save(productC);

    // 2. ACT: Try to reserve batch (productC will fail)
    BatchReserveStockRequest request = BatchReserveStockRequest.builder()
        .storeId(store.getId())
        .items(List.of(
            BatchReserveItem.builder()
                .productId(productA.getId())
                .quantity(3) // OK: 10 available
                .build(),
            BatchReserveItem.builder()
                .productId(productB.getId())
                .quantity(2) // OK: 5 available
                .build(),
            BatchReserveItem.builder()
                .productId(productC.getId())
                .quantity(5) // FAIL: only 2 available
                .build()))
        .build();

    // 3. ASSERT: Exception thrown
    assertThrows(Exception.class, () -> {
      productService.batchReserveStock(request);
    });

    // 4. ASSERT: ALL products unchanged (transaction rollback)
    Product unchangedA = productRepository.findById(productA.getId()).orElseThrow();
    assertEquals(10, unchangedA.getStock(), "Product A stock should be UNCHANGED (rollback)");

    Product unchangedB = productRepository.findById(productB.getId()).orElseThrow();
    assertEquals(5, unchangedB.getStock(), "Product B stock should be UNCHANGED (rollback)");

    Product unchangedC = productRepository.findById(productC.getId()).orElseThrow();
    assertEquals(2, unchangedC.getStock(), "Product C stock should be UNCHANGED");

    // 5. ASSERT: NO outbox events created (transaction rolled back)
    List<OutboxEvent> events = outboxRepository.findAll();
    assertEquals(0, events.size(), "Should have NO outbox events on rollback");
  }

  @Test
  void should_rollback_entire_batch_if_one_product_is_unavailable() {
    // 1. ARRANGE: Create products, one is unavailable
    Product productA = Product.builder()
        .name("Laptop")
        .stock(10)
        .isAvailable(true)
        .price(BigDecimal.valueOf(1000))
        .category(category)
        .build();
    productA = productRepository.save(productA);

    Product productB = Product.builder()
        .name("Mouse")
        .stock(5)
        .isAvailable(false) // UNAVAILABLE!
        .price(BigDecimal.valueOf(50))
        .category(category)
        .build();
    productB = productRepository.save(productB);

    // 2. ACT
    BatchReserveStockRequest request = BatchReserveStockRequest.builder()
        .storeId(store.getId())
        .items(List.of(
            BatchReserveItem.builder()
                .productId(productA.getId())
                .quantity(3)
                .build(),
            BatchReserveItem.builder()
                .productId(productB.getId())
                .quantity(2)
                .build()))
        .build();

    // 3. ASSERT: Exception thrown
    assertThrows(Exception.class, () -> {
      productService.batchReserveStock(request);
    });

    // 4. ASSERT: Product A unchanged (rollback)
    Product unchangedA = productRepository.findById(productA.getId()).orElseThrow();
    assertEquals(10, unchangedA.getStock(), "Product A should be unchanged");
  }

  @Test
  void should_rollback_entire_batch_if_product_not_found() {
    // 1. ARRANGE
    Product productA = Product.builder()
        .name("Laptop")
        .stock(10)
        .isAvailable(true)
        .price(BigDecimal.valueOf(1000))
        .category(category)
        .build();
    productA = productRepository.save(productA);

    UUID nonExistentProductId = UUID.randomUUID();

    // 2. ACT
    BatchReserveStockRequest request = BatchReserveStockRequest.builder()
        .storeId(store.getId())
        .items(List.of(
            BatchReserveItem.builder()
                .productId(productA.getId())
                .quantity(3)
                .build(),
            BatchReserveItem.builder()
                .productId(nonExistentProductId)
                .quantity(1)
                .build()))
        .build();

    // 3. ASSERT
    assertThrows(Exception.class, () -> {
      productService.batchReserveStock(request);
    });

    // 4. ASSERT: Product A unchanged
    Product unchangedA = productRepository.findById(productA.getId()).orElseThrow();
    assertEquals(10, unchangedA.getStock());
  }

  @Test
  void should_rollback_batch_restore_if_one_product_fails() {
    // 1. ARRANGE: Create products
    Product productA = Product.builder()
        .name("Laptop")
        .stock(7) // Already reserved 3
        .isAvailable(true)
        .price(BigDecimal.valueOf(1000))
        .category(category)
        .build();
    productA = productRepository.save(productA);

    UUID nonExistentProductId = UUID.randomUUID();

    // 2. ACT: Try to restore batch (one product doesn't exist)
    BatchReserveStockRequest restoreRequest = BatchReserveStockRequest.builder()
        .storeId(store.getId())
        .items(List.of(
            BatchReserveItem.builder()
                .productId(productA.getId())
                .quantity(3)
                .build(),
            BatchReserveItem.builder()
                .productId(nonExistentProductId)
                .quantity(2)
                .build()))
        .build();

    // 3. ASSERT: Exception thrown
    assertThrows(Exception.class, () -> {
      productService.batchRestoreStock(restoreRequest);
    });

    // 4. ASSERT: Product A stock unchanged (rollback)
    Product unchangedA = productRepository.findById(productA.getId()).orElseThrow();
    assertEquals(7, unchangedA.getStock(), "Stock should be unchanged on rollback");
  }

  @Test
  void should_succeed_when_all_products_have_sufficient_stock() {
    // 1. ARRANGE: Create 3 products with sufficient stock
    Product productA = Product.builder()
        .name("Laptop")
        .stock(10)
        .isAvailable(true)
        .price(BigDecimal.valueOf(1000))
        .category(category)
        .build();
    productA = productRepository.save(productA);

    Product productB = Product.builder()
        .name("Mouse")
        .stock(5)
        .isAvailable(true)
        .price(BigDecimal.valueOf(50))
        .category(category)
        .build();
    productB = productRepository.save(productB);

    Product productC = Product.builder()
        .name("Keyboard")
        .stock(8)
        .isAvailable(true)
        .price(BigDecimal.valueOf(100))
        .category(category)
        .build();
    productC = productRepository.save(productC);

    // 2. ACT: Reserve batch
    BatchReserveStockRequest request = BatchReserveStockRequest.builder()
        .storeId(store.getId())
        .items(List.of(
            BatchReserveItem.builder()
                .productId(productA.getId())
                .quantity(3)
                .build(),
            BatchReserveItem.builder()
                .productId(productB.getId())
                .quantity(2)
                .build(),
            BatchReserveItem.builder()
                .productId(productC.getId())
                .quantity(4)
                .build()))
        .build();

    var responses = productService.batchReserveStock(request);

    // 3. ASSERT: All successful
    assertEquals(3, responses.size());
    assertTrue(responses.stream().allMatch(r -> r.isSuccess()));

    // 4. ASSERT: All stocks decreased
    Product updatedA = productRepository.findById(productA.getId()).orElseThrow();
    assertEquals(7, updatedA.getStock());

    Product updatedB = productRepository.findById(productB.getId()).orElseThrow();
    assertEquals(3, updatedB.getStock());

    Product updatedC = productRepository.findById(productC.getId()).orElseThrow();
    assertEquals(4, updatedC.getStock());
  }

  @Test
  void should_restore_all_products_in_batch_successfully() {
    // 1. ARRANGE: Create products with reduced stock
    Product productA = Product.builder()
        .name("Laptop")
        .stock(7) // Was 10, reserved 3
        .isAvailable(true)
        .price(BigDecimal.valueOf(1000))
        .category(category)
        .build();
    productA = productRepository.save(productA);

    Product productB = Product.builder()
        .name("Mouse")
        .stock(3) // Was 5, reserved 2
        .isAvailable(true)
        .price(BigDecimal.valueOf(50))
        .category(category)
        .build();
    productB = productRepository.save(productB);

    // 2. ACT: Restore batch
    BatchReserveStockRequest restoreRequest = BatchReserveStockRequest.builder()
        .storeId(store.getId())
        .items(List.of(
            BatchReserveItem.builder()
                .productId(productA.getId())
                .quantity(3)
                .build(),
            BatchReserveItem.builder()
                .productId(productB.getId())
                .quantity(2)
                .build()))
        .build();

    productService.batchRestoreStock(restoreRequest);

    // 3. ASSERT: All stocks restored
    Product restoredA = productRepository.findById(productA.getId()).orElseThrow();
    assertEquals(10, restoredA.getStock());

    Product restoredB = productRepository.findById(productB.getId()).orElseThrow();
    assertEquals(5, restoredB.getStock());
  }

  @Test
  void should_mark_product_available_when_batch_restore_brings_stock_above_zero() {
    // 1. ARRANGE: Product with 0 stock (unavailable)
    Product product = Product.builder()
        .name("Laptop")
        .stock(0)
        .isAvailable(false)
        .price(BigDecimal.valueOf(1000))
        .category(category)
        .build();
    product = productRepository.save(product);

    // 2. ACT: Restore stock
    BatchReserveStockRequest restoreRequest = BatchReserveStockRequest.builder()
        .storeId(store.getId())
        .items(List.of(
            BatchReserveItem.builder()
                .productId(product.getId())
                .quantity(5)
                .build()))
        .build();

    productService.batchRestoreStock(restoreRequest);

    // 3. ASSERT: Product available again
    Product restored = productRepository.findById(product.getId()).orElseThrow();
    assertEquals(5, restored.getStock());
    assertTrue(restored.getIsAvailable(), "Should be available when stock > 0");
  }
}
