package com.yads.storeservice.service;

import com.yads.common.dto.BatchReserveItem;
import com.yads.common.dto.BatchReserveStockRequest;
import com.yads.common.dto.ReserveStockRequest;
import com.yads.common.exception.AccessDeniedException;
import com.yads.common.exception.InsufficientStockException;
import com.yads.storeservice.dto.ProductRequest;
import com.yads.storeservice.dto.ProductResponse;
import com.yads.storeservice.event.ProductUpdateEvent;
import com.yads.storeservice.mapper.ProductMapper;
import com.yads.storeservice.model.Category;
import com.yads.storeservice.model.Product;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.repository.CategoryRepository;
import com.yads.storeservice.repository.ProductRepository;
import com.yads.storeservice.services.ProductServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private ProductMapper productMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private EntityManager entityManager; // refresh() için gerekli

    @InjectMocks
    private ProductServiceImpl productService;

    private UUID ownerId;
    private UUID storeId;
    private UUID categoryId;
    private UUID productId;
    private Product product;
    private Category category;
    private Store store;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        productId = UUID.randomUUID();

        store = new Store();
        store.setId(storeId);
        store.setOwnerId(ownerId);

        category = new Category();
        category.setId(categoryId);
        category.setStore(store);

        product = new Product();
        product.setId(productId);
        product.setName("Test Product");
        product.setStock(10);
        product.setPrice(BigDecimal.TEN);
        product.setIsAvailable(true);
        product.setCategory(category);
    }

    // --- CREATE PRODUCT TESTS ---

    @Test
    void createProduct_Success() {
        // Arrange
        ProductRequest request = ProductRequest.builder().name("New P").price(BigDecimal.ONE).stock(5).build();

        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(productMapper.toProduct(request)).thenReturn(product);
        when(productRepository.save(any(Product.class))).thenReturn(product);
        when(productMapper.toProductResponse(any())).thenReturn(ProductResponse.builder().id(productId).build());

        // Act
        productService.createProduct(categoryId, request, ownerId);

        // Assert
        verify(productRepository).save(product);
        verify(eventPublisher).publishEvent(any(ProductUpdateEvent.class));
    }

    @Test
    void createProduct_AccessDenied_WrongOwner() {
        // Arrange
        UUID otherOwnerId = UUID.randomUUID();
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        // Act & Assert
        assertThatThrownBy(() -> productService.createProduct(categoryId, mock(ProductRequest.class), otherOwnerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- RESERVE STOCK TESTS (Atomic & Concurrency) ---

    @Test
    void reserveProduct_Success_Atomic() {
        // Arrange
        ReserveStockRequest request = ReserveStockRequest.builder()
                .storeId(storeId)
                .quantity(2)
                .build();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        // Atomik update başarılı (1 satır güncellendi)
        when(productRepository.decreaseStock(productId, 2)).thenReturn(1);
        when(productMapper.toProductResponse(any())).thenReturn(ProductResponse.builder().build());

        // Act
        productService.reserveProduct(productId, request);

        // Assert
        // refresh çağrılmalı çünkü DB'deki stok memory'den farklı artık
        verify(entityManager).refresh(product);
        verify(eventPublisher).publishEvent(any(ProductUpdateEvent.class));
    }

    @Test
    void reserveProduct_Fail_InsufficientStock_LogicCheck() {
        // Arrange
        product.setStock(1); // Stok az
        ReserveStockRequest request = ReserveStockRequest.builder()
                .storeId(storeId)
                .quantity(5) // İstenen çok
                .build();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        // Act & Assert
        assertThatThrownBy(() -> productService.reserveProduct(productId, request))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Not enough stock");

        // Veritabanına hiç gitmemeli
        verify(productRepository, never()).decreaseStock(any(), anyInt());
    }

    @Test
    void reserveProduct_Fail_RaceCondition_AtomicUpdateReturnsZero() {
        // Arrange
        product.setStock(5); // Memory'de stok var görünüyor
        ReserveStockRequest request = ReserveStockRequest.builder()
                .storeId(storeId)
                .quantity(2)
                .build();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        // KRİTİK: decreaseStock 0 dönüyor! (Demek ki o an başka thread stoğu bitirdi)
        when(productRepository.decreaseStock(productId, 2)).thenReturn(0);

        // Act & Assert
        assertThatThrownBy(() -> productService.reserveProduct(productId, request))
                .isInstanceOf(InsufficientStockException.class); // Hata fırlatmalı

        verify(eventPublisher, never()).publishEvent(any()); // Event atılmamalı
    }

    // --- BATCH RESERVATION TESTS (Transaction Atomicity Simulation) ---

    @Test
    void batchReserveStock_Success() {
        // Arrange
        BatchReserveItem item1 = new BatchReserveItem(productId, 2);
        BatchReserveStockRequest request = BatchReserveStockRequest.builder()
                .storeId(storeId)
                .items(List.of(item1))
                .build();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.decreaseStock(productId, 2)).thenReturn(1);

        // Act
        productService.batchReserveStock(request);

        // Assert
        verify(eventPublisher, times(1)).publishEvent(any(ProductUpdateEvent.class));
    }

    @Test
    void batchReserveStock_Fail_RollbackScenario() {
        // Arrange
        // 2 ürün var: biri başarılı olacak, diğeri fail edecek
        UUID productId2 = UUID.randomUUID();
        Product product2 = new Product();
        product2.setId(productId2);
        product2.setStock(1); // Stok yetersiz
        product2.setIsAvailable(true);
        product2.setCategory(category); // Aynı store

        BatchReserveItem item1 = new BatchReserveItem(productId, 2); // OK
        BatchReserveItem item2 = new BatchReserveItem(productId2, 5); // Yetersiz

        BatchReserveStockRequest request = BatchReserveStockRequest.builder()
                .storeId(storeId)
                .items(List.of(item1, item2))
                .build();

        // Mocking behaviors
        // 1. Ürün OK
        doReturn(Optional.of(product)).when(productRepository).findById(productId);
        doReturn(1).when(productRepository).decreaseStock(productId, 2);

        // 2. Ürün Fail
        doReturn(Optional.of(product2)).when(productRepository).findById(productId2);
        // decreaseStock çağrılmadan exception fırlatılacak (if check)

        // Act & Assert
        assertThatThrownBy(() -> productService.batchReserveStock(request))
                .isInstanceOf(InsufficientStockException.class);

        // Verify:
        // İlk ürün için update çağrıldı (ama @Transactional rollback yapacak gerçek hayatta)
        verify(productRepository).decreaseStock(productId, 2);

        // İkinci ürün için update ÇAĞRILMADI (çünkü logic check fail oldu)
        verify(productRepository, never()).decreaseStock(productId2, 5);

        // Event fırlatılmış olabilir (ilk ürün için), ancak TransactionalEventListener
        // "AFTER_COMMIT" modunda olduğu için, transaction rollback olunca bu event RabbitMQ'ya GİTMEYECEKTİR.
        // Unit testte verify(eventPublisher) bunu yakalar, ancak entegrasyonun doğru olduğunu biliyoruz.
    }

    // --- RESTORE STOCK TESTS ---

    @Test
    void restoreStock_Success_ReEnablesAvailability() {
        // Arrange
        product.setStock(0);
        product.setIsAvailable(false); // Başta kapalı

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(product)).thenReturn(product);

        // Act
        productService.restoreStock(productId, 5, storeId);

        // Assert
        assertThat(product.getStock()).isEqualTo(5);
        assertThat(product.getIsAvailable()).isTrue(); // Stok gelince açılmalı

        verify(productRepository).save(product);
        verify(eventPublisher).publishEvent(any(ProductUpdateEvent.class));
    }
}