package com.yads.storeservice;

import com.yads.storeservice.model.Category;
import com.yads.storeservice.model.Product;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.model.StoreType;
import com.yads.storeservice.repository.CategoryRepository;
import com.yads.storeservice.repository.ProductRepository;
import com.yads.storeservice.repository.StoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests atomic stock updates under concurrent load.
 *
 * CRITICAL DIFFERENCE from order-service:
 * - store-service uses atomic DB queries (decreaseStock with WHERE clause)
 * - order-service uses @Version optimistic locking
 *
 * Both approaches prevent lost updates, but with different mechanisms:
 * - Atomic query: UPDATE ... WHERE stock >= :quantity (0 rows if insufficient)
 * - Optimistic locking: Version mismatch throws
 * ObjectOptimisticLockingFailureException
 */
public class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private CategoryRepository categoryRepository;

  @Autowired
  private StoreRepository storeRepository;

  @Autowired
  private TestProductService testProductService;

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
  void clear() {
    productRepository.deleteAll();
    categoryRepository.deleteAll();
    storeRepository.deleteAll();
  }

  @RepeatedTest(5) // Run 5 times to catch intermittent race conditions
  void should_handle_concurrent_stock_reservations_atomically() throws InterruptedException, ExecutionException {
    // 1. ARRANGE: Product with 10 items
    Product product = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(10)
        .isAvailable(true)
        .category(category)
        .build();
    product = productRepository.save(product);
    UUID productId = product.getId();

    // 2. ACT: Two threads trying to reserve 6 items each (total 12, but only 10
    // available)
    ExecutorService executor = Executors.newFixedThreadPool(2);

    Callable<Integer> reserveTask = () -> {
      // Atomic update: decreaseStock with WHERE clause
      return testProductService.decreaseStock(productId, 6);
    };

    Future<Integer> result1 = executor.submit(reserveTask);
    Future<Integer> result2 = executor.submit(reserveTask);

    Integer updatedRows1 = result1.get();
    Integer updatedRows2 = result2.get();

    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    // 3. ASSERT: Only ONE update should succeed
    int successCount = (updatedRows1 > 0 ? 1 : 0) + (updatedRows2 > 0 ? 1 : 0);
    assertEquals(1, successCount, "Only one thread should succeed due to atomic WHERE clause");

    // One should return 1 (success), other should return 0 (fail)
    assertTrue((updatedRows1 == 1 && updatedRows2 == 0) || (updatedRows1 == 0 && updatedRows2 == 1),
        "One update should succeed (1), other should fail (0)");

    // 4. VERIFY: Final stock should be 4 (10 - 6)
    Product finalProduct = productRepository.findById(productId).orElseThrow();
    assertEquals(4, finalProduct.getStock(), "Stock should be 4 (only one reservation succeeded)");
    assertTrue(finalProduct.getIsAvailable());
  }

  @RepeatedTest(5) // Run 5 times to catch intermittent race conditions
  void should_prevent_negative_stock_with_atomic_query() throws InterruptedException, ExecutionException {
    // 1. ARRANGE: Product with only 3 items
    Product product = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(3)
        .isAvailable(true)
        .category(category)
        .build();
    product = productRepository.save(product);
    UUID productId = product.getId();

    // 2. ACT: Multiple threads trying to reserve 2 items each
    ExecutorService executor = Executors.newFixedThreadPool(5);
    List<Future<Integer>> results = new ArrayList<>();

    for (int i = 0; i < 5; i++) {
      results.add(executor.submit(() -> testProductService.decreaseStock(productId, 2)));
    }

    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    // 3. ASSERT: Only first thread should succeed (3 - 2 = 1), rest fail
    int successCount = 0;
    for (Future<Integer> result : results) {
      if (result.get() > 0) {
        successCount++;
      }
    }

    assertEquals(1, successCount, "Only ONE reservation should succeed");

    // 4. VERIFY: Final stock should be 1 (3 - 2)
    Product finalProduct = productRepository.findById(productId).orElseThrow();
    assertEquals(1, finalProduct.getStock());
    assertTrue(finalProduct.getIsAvailable());

    // Stock should NEVER go negative
    assertTrue(finalProduct.getStock() >= 0, "Stock must never be negative");
  }

  @RepeatedTest(3) // Run 3 times (high-load test is slower)
  void should_maintain_consistency_under_high_concurrent_load() throws InterruptedException {
    // 1. ARRANGE: Product with 100 items
    Product product = Product.builder()
        .name("Laptop")
        .price(BigDecimal.valueOf(1000))
        .stock(100)
        .isAvailable(true)
        .category(category)
        .build();
    product = productRepository.save(product);
    UUID productId = product.getId();

    // 2. ACT: 20 threads each trying to reserve 5 items (total 100)
    ExecutorService executor = Executors.newFixedThreadPool(20);
    CountDownLatch latch = new CountDownLatch(20);
    List<Future<Integer>> results = new ArrayList<>();

    for (int i = 0; i < 20; i++) {
      results.add(executor.submit(() -> {
        try {
          latch.countDown();
          latch.await(); // All threads start at the same time
          return testProductService.decreaseStock(productId, 5);
        } catch (InterruptedException e) {
          return 0;
        }
      }));
    }

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    // 3. ASSERT: Exactly 20 should succeed
    int successCount = 0;
    for (Future<Integer> result : results) {
      try {
        if (result.get() > 0) {
          successCount++;
        }
      } catch (Exception e) {
        // ignore
      }
    }

    assertEquals(20, successCount, "All 20 reservations should succeed (100 / 5 = 20)");

    // 4. VERIFY: Final stock should be 0
    Product finalProduct = productRepository.findById(productId).orElseThrow();
    assertEquals(0, finalProduct.getStock());
    assertFalse(finalProduct.getIsAvailable(), "Should be unavailable when stock=0");
  }
}
