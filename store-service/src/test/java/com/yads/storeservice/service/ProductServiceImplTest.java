package com.yads.storeservice.service;

import com.yads.common.dto.BatchReserveItem;
import com.yads.common.dto.BatchReserveStockRequest;
import com.yads.common.dto.BatchReserveStockResponse;
import com.yads.common.dto.ReserveStockRequest;
import com.yads.common.exception.AccessDeniedException;
import com.yads.common.exception.InsufficientStockException;
import com.yads.common.exception.ResourceNotFoundException;
import com.yads.storeservice.dto.ProductRequest;
import com.yads.storeservice.dto.ProductResponse;
import com.yads.storeservice.event.ProductDeleteEvent;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService Unit Tests")
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private ProductServiceImpl productService;

    private UUID ownerId;
    private UUID storeId;
    private UUID categoryId;
    private UUID productId;
    private Product product;
    private Category category;
    private Store store;
    private ProductRequest productRequest;
    private ProductResponse productResponse;

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

        productRequest = ProductRequest.builder()
                .name("Test Product")
                .description("Test Description")
                .price(BigDecimal.TEN)
                .stock(10)
                .build();

        productResponse = ProductResponse.builder()
                .id(productId)
                .name("Test Product")
                .price(BigDecimal.TEN)
                .stock(10)
                .isAvailable(true)
                .build();
    }

    @Nested
    @DisplayName("Create Product Tests")
    class CreateProductTests {

        @Test
        @DisplayName("should create product successfully when owner is authorized")
        void shouldCreateProductSuccessfully() {
            // Arrange
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(productMapper.toProduct(productRequest)).thenReturn(product);
            when(productRepository.save(any(Product.class))).thenReturn(product);
            when(productMapper.toProductResponse(product)).thenReturn(productResponse);

            // Act
            ProductResponse result = productService.createProduct(categoryId, productRequest, ownerId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(productId);
            verify(productRepository).save(product);
            verify(eventPublisher).publishEvent(any(ProductUpdateEvent.class));
        }

        @Test
        @DisplayName("should throw AccessDeniedException when owner is different")
        void shouldThrowAccessDeniedExceptionWhenOwnerIsDifferent() {
            // Arrange
            UUID differentOwnerId = UUID.randomUUID();
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

            // Act & Assert
            assertThatThrownBy(() -> productService.createProduct(categoryId, productRequest, differentOwnerId))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("not authorized");

            verify(productRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when category not found")
        void shouldThrowResourceNotFoundExceptionWhenCategoryNotFound() {
            // Arrange
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> productService.createProduct(categoryId, productRequest, ownerId))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("should set availability to false when stock is zero")
        void shouldSetAvailabilityToFalseWhenStockIsZero() {
            // Arrange
            ProductRequest zeroStockRequest = ProductRequest.builder()
                    .name("Zero Stock")
                    .price(BigDecimal.TEN)
                    .stock(0)
                    .build();

            Product zeroStockProduct = new Product();
            zeroStockProduct.setStock(0);

            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(productMapper.toProduct(zeroStockRequest)).thenReturn(zeroStockProduct);
            when(productRepository.save(any(Product.class))).thenReturn(zeroStockProduct);
            when(productMapper.toProductResponse(any())).thenReturn(productResponse);

            // Act
            productService.createProduct(categoryId, zeroStockRequest, ownerId);

            // Assert
            verify(productRepository).save(argThat(p -> !p.getIsAvailable()));
        }

        @Test
        @DisplayName("should publish ProductUpdateEvent with correct event type")
        void shouldPublishProductUpdateEventWithCorrectType() {
            // Arrange
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(productMapper.toProduct(productRequest)).thenReturn(product);
            when(productRepository.save(any(Product.class))).thenReturn(product);
            when(productMapper.toProductResponse(product)).thenReturn(productResponse);

            ArgumentCaptor<ProductUpdateEvent> eventCaptor = ArgumentCaptor.forClass(ProductUpdateEvent.class);

            // Act
            productService.createProduct(categoryId, productRequest, ownerId);

            // Assert
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            ProductUpdateEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getProduct()).isEqualTo(product);
            assertThat(capturedEvent.getRoutingKey()).isEqualTo("product.created");
        }
    }

    @Nested
    @DisplayName("Update Product Tests")
    class UpdateProductTests {

        @Test
        @DisplayName("should update product successfully when owner is authorized")
        void shouldUpdateProductSuccessfully() {
            // Arrange
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.save(product)).thenReturn(product);
            when(productMapper.toProductResponse(product)).thenReturn(productResponse);

            // Act
            ProductResponse result = productService.updateProduct(productId, productRequest, ownerId);

            // Assert
            assertThat(result).isNotNull();
            verify(productMapper).updateProductFromRequest(productRequest, product);
            verify(productRepository).save(product);
            verify(eventPublisher).publishEvent(any(ProductUpdateEvent.class));
        }

        @Test
        @DisplayName("should throw AccessDeniedException when owner is different")
        void shouldThrowAccessDeniedExceptionWhenOwnerIsDifferent() {
            // Arrange
            UUID differentOwnerId = UUID.randomUUID();
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));

            // Act & Assert
            assertThatThrownBy(() -> productService.updateProduct(productId, productRequest, differentOwnerId))
                    .isInstanceOf(AccessDeniedException.class);

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when product not found")
        void shouldThrowResourceNotFoundExceptionWhenProductNotFound() {
            // Arrange
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> productService.updateProduct(productId, productRequest, ownerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Delete Product Tests")
    class DeleteProductTests {

        @Test
        @DisplayName("should delete product successfully when owner is authorized")
        void shouldDeleteProductSuccessfully() {
            // Arrange
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));

            // Act
            productService.deleteProduct(productId, ownerId);

            // Assert
            verify(productRepository).delete(product);
            verify(eventPublisher).publishEvent(any(ProductDeleteEvent.class));
        }

        @Test
        @DisplayName("should throw AccessDeniedException when owner is different")
        void shouldThrowAccessDeniedExceptionWhenOwnerIsDifferent() {
            // Arrange
            UUID differentOwnerId = UUID.randomUUID();
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));

            // Act & Assert
            assertThatThrownBy(() -> productService.deleteProduct(productId, differentOwnerId))
                    .isInstanceOf(AccessDeniedException.class);

            verify(productRepository, never()).delete(any());
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when product not found")
        void shouldThrowResourceNotFoundExceptionWhenProductNotFound() {
            // Arrange
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> productService.deleteProduct(productId, ownerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("should publish ProductDeleteEvent with correct product")
        void shouldPublishProductDeleteEventWithCorrectProduct() {
            // Arrange
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            ArgumentCaptor<ProductDeleteEvent> eventCaptor = ArgumentCaptor.forClass(ProductDeleteEvent.class);

            // Act
            productService.deleteProduct(productId, ownerId);

            // Assert
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            ProductDeleteEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.getProductId()).isEqualTo(productId);
        }
    }

    @Nested
    @DisplayName("Get Product Tests")
    class GetProductTests {

        @Test
        @DisplayName("should get product by id successfully")
        void shouldGetProductByIdSuccessfully() {
            // Arrange
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productMapper.toProductResponse(product)).thenReturn(productResponse);

            // Act
            ProductResponse result = productService.getProductById(productId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(productId);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when product not found")
        void shouldThrowResourceNotFoundExceptionWhenProductNotFound() {
            // Arrange
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> productService.getProductById(productId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("should get products by category successfully")
        void shouldGetProductsByCategorySuccessfully() {
            // Arrange
            List<Product> products = List.of(product);
            when(categoryRepository.existsById(categoryId)).thenReturn(true);
            when(productRepository.findByCategoryId(categoryId)).thenReturn(products);
            when(productMapper.toProductResponse(product)).thenReturn(productResponse);

            // Act
            List<ProductResponse> result = productService.getProductsByCategory(categoryId);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(productId);
        }

        @Test
        @DisplayName("should get available products by category successfully")
        void shouldGetAvailableProductsByCategorySuccessfully() {
            // Arrange
            List<Product> products = List.of(product);
            when(categoryRepository.existsById(categoryId)).thenReturn(true);
            when(productRepository.findByCategoryIdAndIsAvailableTrue(categoryId)).thenReturn(products);
            when(productMapper.toProductResponse(product)).thenReturn(productResponse);

            // Act
            List<ProductResponse> result = productService.getAvailableProductsByCategory(categoryId);

            // Assert
            assertThat(result).hasSize(1);
            verify(productRepository).findByCategoryIdAndIsAvailableTrue(categoryId);
        }

        @Test
        @DisplayName("should get products by store successfully")
        void shouldGetProductsByStoreSuccessfully() {
            // Arrange
            List<Product> products = List.of(product);
            when(productRepository.findByCategoryStoreId(storeId)).thenReturn(products);
            when(productMapper.toProductResponse(product)).thenReturn(productResponse);

            // Act
            List<ProductResponse> result = productService.getProductsByStore(storeId);

            // Assert
            assertThat(result).hasSize(1);
            verify(productRepository).findByCategoryStoreId(storeId);
        }

        @Test
        @DisplayName("should return empty list when no products found")
        void shouldReturnEmptyListWhenNoProductsFound() {
            // Arrange
            when(categoryRepository.existsById(categoryId)).thenReturn(true);
            when(productRepository.findByCategoryId(categoryId)).thenReturn(Collections.emptyList());

            // Act
            List<ProductResponse> result = productService.getProductsByCategory(categoryId);

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Search Product Tests")
    class SearchProductTests {

        @Test
        @DisplayName("should search products by name successfully")
        void shouldSearchProductsByNameSuccessfully() {
            // Arrange
            String searchName = "Test";
            List<Product> products = List.of(product);
            when(productRepository.findByNameContainingIgnoreCase(searchName)).thenReturn(products);
            when(productMapper.toProductResponse(product)).thenReturn(productResponse);

            // Act
            List<ProductResponse> result = productService.searchProductsByName(searchName);

            // Assert
            assertThat(result).hasSize(1);
            verify(productRepository).findByNameContainingIgnoreCase(searchName);
        }

        @Test
        @DisplayName("should return empty list when no products match search")
        void shouldReturnEmptyListWhenNoProductsMatchSearch() {
            // Arrange
            String searchName = "NonExistent";
            when(productRepository.findByNameContainingIgnoreCase(searchName)).thenReturn(Collections.emptyList());

            // Act
            List<ProductResponse> result = productService.searchProductsByName(searchName);

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Stock Management Tests")
    class StockManagementTests {

        @Test
        @DisplayName("should update stock successfully when owner is authorized")
        void shouldUpdateStockSuccessfully() {
            // Arrange
            Integer newStock = 20;
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.save(product)).thenReturn(product);
            when(productMapper.toProductResponse(product)).thenReturn(productResponse);

            // Act
            ProductResponse result = productService.updateStock(productId, newStock, ownerId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(product.getStock()).isEqualTo(newStock);
            verify(productRepository).save(product);
            verify(eventPublisher).publishEvent(any(ProductUpdateEvent.class));
        }

        @Test
        @DisplayName("should throw AccessDeniedException when owner is different for stock update")
        void shouldThrowAccessDeniedExceptionWhenOwnerIsDifferentForStockUpdate() {
            // Arrange
            UUID differentOwnerId = UUID.randomUUID();
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));

            // Act & Assert
            assertThatThrownBy(() -> productService.updateStock(productId, 20, differentOwnerId))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("should toggle availability successfully when owner is authorized")
        void shouldToggleAvailabilitySuccessfully() {
            // Arrange
            boolean initialAvailability = product.getIsAvailable();
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.save(product)).thenReturn(product);
            when(productMapper.toProductResponse(product)).thenReturn(productResponse);

            // Act
            ProductResponse result = productService.toggleAvailability(productId, ownerId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(product.getIsAvailable()).isEqualTo(!initialAvailability);
            verify(productRepository).save(product);
            verify(eventPublisher).publishEvent(any(ProductUpdateEvent.class));
        }

        @Test
        @DisplayName("should throw AccessDeniedException when owner is different for toggle availability")
        void shouldThrowAccessDeniedExceptionWhenOwnerIsDifferentForToggleAvailability() {
            // Arrange
            UUID differentOwnerId = UUID.randomUUID();
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));

            // Act & Assert
            assertThatThrownBy(() -> productService.toggleAvailability(productId, differentOwnerId))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("Reserve Stock Tests")
    class ReserveStockTests {

        @Test
        @DisplayName("should reserve stock successfully with atomic update")
        void shouldReserveStockSuccessfullyWithAtomicUpdate() {
            // Arrange
            ReserveStockRequest request = ReserveStockRequest.builder()
                    .storeId(storeId)
                    .quantity(2)
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.decreaseStock(productId, 2)).thenReturn(1);
            when(productMapper.toProductResponse(product)).thenReturn(productResponse);

            // Act
            ProductResponse result = productService.reserveProduct(productId, request);

            // Assert
            assertThat(result).isNotNull();
            verify(entityManager).refresh(product);
            verify(eventPublisher).publishEvent(any(ProductUpdateEvent.class));
        }

        @Test
        @DisplayName("should throw InsufficientStockException when requested quantity exceeds available stock")
        void shouldThrowInsufficientStockExceptionWhenQuantityExceedsStock() {
            // Arrange
            product.setStock(1);
            ReserveStockRequest request = ReserveStockRequest.builder()
                    .storeId(storeId)
                    .quantity(5)
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(product));

            // Act & Assert
            assertThatThrownBy(() -> productService.reserveProduct(productId, request))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("Not enough stock");

            verify(productRepository, never()).decreaseStock(any(), anyInt());
        }

        @Test
        @DisplayName("should throw InsufficientStockException when atomic update returns zero due to race condition")
        void shouldThrowInsufficientStockExceptionWhenAtomicUpdateReturnsZero() {
            // Arrange
            product.setStock(5);
            ReserveStockRequest request = ReserveStockRequest.builder()
                    .storeId(storeId)
                    .quantity(2)
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.decreaseStock(productId, 2)).thenReturn(0);

            // Act & Assert
            assertThatThrownBy(() -> productService.reserveProduct(productId, request))
                    .isInstanceOf(InsufficientStockException.class);

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when product not found")
        void shouldThrowResourceNotFoundExceptionWhenProductNotFound() {
            // Arrange
            ReserveStockRequest request = ReserveStockRequest.builder()
                    .storeId(storeId)
                    .quantity(2)
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> productService.reserveProduct(productId, request))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when product store mismatch")
        void shouldThrowIllegalArgumentExceptionWhenStoreIdMismatch() {
            // Arrange
            UUID differentStoreId = UUID.randomUUID();
            ReserveStockRequest request = ReserveStockRequest.builder()
                    .storeId(differentStoreId)
                    .quantity(2)
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(product));

            // Act & Assert
            assertThatThrownBy(() -> productService.reserveProduct(productId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to");
        }
    }

    @Nested
    @DisplayName("Batch Reserve Stock Tests")
    class BatchReserveStockTests {

        @Test
        @DisplayName("should batch reserve stock successfully for multiple products")
        void shouldBatchReserveStockSuccessfully() {
            // Arrange
            BatchReserveItem item1 = new BatchReserveItem(productId, 2);
            BatchReserveStockRequest request = BatchReserveStockRequest.builder()
                    .storeId(storeId)
                    .items(List.of(item1))
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.decreaseStock(productId, 2)).thenReturn(1);

            // Act
            List<BatchReserveStockResponse> result = productService.batchReserveStock(request);

            // Assert
            assertThat(result).hasSize(1);
            verify(eventPublisher, times(1)).publishEvent(any(ProductUpdateEvent.class));
        }

        @Test
        @DisplayName("should rollback entire batch when one product fails")
        void shouldRollbackEntireBatchWhenOneProductFails() {
            // Arrange
            UUID productId2 = UUID.randomUUID();
            Product product2 = new Product();
            product2.setId(productId2);
            product2.setStock(1);
            product2.setIsAvailable(true);
            product2.setCategory(category);

            BatchReserveItem item1 = new BatchReserveItem(productId, 2);
            BatchReserveItem item2 = new BatchReserveItem(productId2, 5);

            BatchReserveStockRequest request = BatchReserveStockRequest.builder()
                    .storeId(storeId)
                    .items(List.of(item1, item2))
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.decreaseStock(productId, 2)).thenReturn(1);
            when(productRepository.findById(productId2)).thenReturn(Optional.of(product2));

            // Act & Assert
            assertThatThrownBy(() -> productService.batchReserveStock(request))
                    .isInstanceOf(InsufficientStockException.class);

            verify(productRepository).decreaseStock(productId, 2);
            verify(productRepository, never()).decreaseStock(productId2, 5);
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when batch items belong to different stores")
        void shouldThrowIllegalArgumentExceptionWhenMultipleStores() {
            // Arrange
            UUID differentStoreId = UUID.randomUUID();
            Store differentStore = new Store();
            differentStore.setId(differentStoreId);

            Category differentCategory = new Category();
            differentCategory.setStore(differentStore);

            Product product2 = new Product();
            product2.setId(UUID.randomUUID());
            product2.setStock(10);
            product2.setCategory(differentCategory);

            BatchReserveItem item1 = new BatchReserveItem(productId, 2);
            BatchReserveItem item2 = new BatchReserveItem(product2.getId(), 3);

            BatchReserveStockRequest request = BatchReserveStockRequest.builder()
                    .storeId(storeId)
                    .items(List.of(item1, item2))
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.decreaseStock(productId, 2)).thenReturn(1);
            when(productRepository.findById(product2.getId())).thenReturn(Optional.of(product2));

            // Act & Assert
            assertThatThrownBy(() -> productService.batchReserveStock(request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Restore Stock Tests")
    class RestoreStockTests {

        @Test
        @DisplayName("should restore stock successfully and re-enable availability")
        void shouldRestoreStockSuccessfullyAndReEnableAvailability() {
            // Arrange
            product.setStock(0);
            product.setIsAvailable(false);

            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.save(product)).thenReturn(product);

            // Act
            productService.restoreStock(productId, 5, storeId);

            // Assert
            assertThat(product.getStock()).isEqualTo(5);
            assertThat(product.getIsAvailable()).isTrue();
            verify(productRepository).save(product);
            verify(eventPublisher).publishEvent(any(ProductUpdateEvent.class));
        }

        @Test
        @DisplayName("should restore stock without changing availability when already available")
        void shouldRestoreStockWithoutChangingAvailabilityWhenAlreadyAvailable() {
            // Arrange
            product.setStock(5);
            product.setIsAvailable(true);

            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.save(product)).thenReturn(product);

            // Act
            productService.restoreStock(productId, 3, storeId);

            // Assert
            assertThat(product.getStock()).isEqualTo(8);
            assertThat(product.getIsAvailable()).isTrue();
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when product not found")
        void shouldThrowResourceNotFoundExceptionWhenProductNotFound() {
            // Arrange
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> productService.restoreStock(productId, 5, storeId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when store mismatch")
        void shouldThrowIllegalArgumentExceptionWhenStoreMismatch() {
            // Arrange
            UUID differentStoreId = UUID.randomUUID();
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));

            // Act & Assert
            assertThatThrownBy(() -> productService.restoreStock(productId, 5, differentStoreId))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Batch Restore Stock Tests")
    class BatchRestoreStockTests {

        @Test
        @DisplayName("should batch restore stock successfully for multiple products")
        void shouldBatchRestoreStockSuccessfully() {
            // Arrange
            product.setStock(0);
            product.setIsAvailable(false);

            BatchReserveItem item1 = new BatchReserveItem(productId, 5);
            BatchReserveStockRequest request = BatchReserveStockRequest.builder()
                    .storeId(storeId)
                    .items(List.of(item1))
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.save(product)).thenReturn(product);

            // Act
            productService.batchRestoreStock(request);

            // Assert
            assertThat(product.getStock()).isEqualTo(5);
            assertThat(product.getIsAvailable()).isTrue();
            verify(eventPublisher).publishEvent(any(ProductUpdateEvent.class));
        }

        @Test
        @DisplayName("should restore stock for all products even if one was already available")
        void shouldRestoreStockForAllProducts() {
            // Arrange
            UUID productId2 = UUID.randomUUID();
            Product product2 = new Product();
            product2.setId(productId2);
            product2.setStock(10);
            product2.setIsAvailable(true);
            product2.setCategory(category);

            product.setStock(0);
            product.setIsAvailable(false);

            BatchReserveItem item1 = new BatchReserveItem(productId, 5);
            BatchReserveItem item2 = new BatchReserveItem(productId2, 3);

            BatchReserveStockRequest request = BatchReserveStockRequest.builder()
                    .storeId(storeId)
                    .items(List.of(item1, item2))
                    .build();

            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productRepository.findById(productId2)).thenReturn(Optional.of(product2));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            productService.batchRestoreStock(request);

            // Assert
            assertThat(product.getStock()).isEqualTo(5);
            assertThat(product.getIsAvailable()).isTrue();
            assertThat(product2.getStock()).isEqualTo(13);
            verify(eventPublisher, times(2)).publishEvent(any(ProductUpdateEvent.class));
        }
    }
}