package com.yads.storeservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yads.common.contracts.ProductEventDto;
import com.yads.storeservice.model.Category;
import com.yads.storeservice.model.OutboxEvent;
import com.yads.storeservice.model.Product;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductEventListener Unit Tests")
class ProductEventListenerTest {

        @Mock
        private OutboxRepository outboxRepository;
        @Mock
        private ObjectMapper objectMapper;

        @InjectMocks
        private ProductEventListener productEventListener;

        private Product product;
        private UUID productId;
        private UUID storeId;
        private Store store;
        private Category category;

        @BeforeEach
        void setUp() {
                productId = UUID.randomUUID();
                storeId = UUID.randomUUID();

                store = new Store();
                store.setId(storeId);
                store.setName("Test Store");

                category = new Category();
                category.setId(UUID.randomUUID());
                category.setName("Test Category");
                category.setStore(store);

                product = new Product();
                product.setId(productId);
                product.setName("Test Product");
                product.setPrice(BigDecimal.valueOf(99.99));
                product.setStock(50);
                product.setIsAvailable(true);
                product.setCategory(category);
        }

        @Nested
        @DisplayName("Product Update Event Tests")
        class ProductUpdateEventTests {

                @Test
                @DisplayName("should save outbox event with correct aggregateType PRODUCT")
                void shouldSaveOutboxEventWithCorrectAggregateType() throws Exception {
                        // Arrange
                        ProductUpdateEvent event = new ProductUpdateEvent(this, product, "product.created");
                        when(objectMapper.writeValueAsString(any(ProductEventDto.class)))
                                        .thenReturn("{\"productId\":\"test\"}");

                        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        productEventListener.handleProductUpdateEvent(event);

                        // Assert
                        verify(outboxRepository).save(outboxCaptor.capture());
                        OutboxEvent savedEvent = outboxCaptor.getValue();
                        assertThat(savedEvent.getAggregateType()).isEqualTo("PRODUCT");
                }

                @Test
                @DisplayName("should save outbox event with productId as aggregateId")
                void shouldSaveOutboxEventWithProductIdAsAggregateId() throws Exception {
                        // Arrange
                        ProductUpdateEvent event = new ProductUpdateEvent(this, product, "product.created");
                        when(objectMapper.writeValueAsString(any(ProductEventDto.class)))
                                        .thenReturn("{\"productId\":\"test\"}");

                        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        productEventListener.handleProductUpdateEvent(event);

                        // Assert
                        verify(outboxRepository).save(outboxCaptor.capture());
                        OutboxEvent savedEvent = outboxCaptor.getValue();
                        assertThat(savedEvent.getAggregateId()).isEqualTo(productId.toString());
                }

                @Test
                @DisplayName("should save outbox event with routingKey as type")
                void shouldSaveOutboxEventWithRoutingKeyAsType() throws Exception {
                        // Arrange
                        ProductUpdateEvent event = new ProductUpdateEvent(this, product, "product.updated");
                        when(objectMapper.writeValueAsString(any(ProductEventDto.class)))
                                        .thenReturn("{\"productId\":\"test\"}");

                        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        productEventListener.handleProductUpdateEvent(event);

                        // Assert
                        verify(outboxRepository).save(outboxCaptor.capture());
                        OutboxEvent savedEvent = outboxCaptor.getValue();
                        assertThat(savedEvent.getType()).isEqualTo("product.updated");
                }

                @Test
                @DisplayName("should serialize ProductEventDto correctly")
                void shouldSerializeProductEventDtoCorrectly() throws Exception {
                        // Arrange
                        ProductUpdateEvent event = new ProductUpdateEvent(this, product, "product.created");
                        when(objectMapper.writeValueAsString(any(ProductEventDto.class)))
                                        .thenReturn("{\"productId\":\"test\"}");

                        ArgumentCaptor<ProductEventDto> dtoCaptor = ArgumentCaptor.forClass(ProductEventDto.class);

                        // Act
                        productEventListener.handleProductUpdateEvent(event);

                        // Assert
                        verify(objectMapper).writeValueAsString(dtoCaptor.capture());
                        ProductEventDto capturedDto = dtoCaptor.getValue();
                        assertThat(capturedDto.getProductId()).isEqualTo(productId);
                        assertThat(capturedDto.getName()).isEqualTo("Test Product");
                        assertThat(capturedDto.getPrice()).isEqualTo(BigDecimal.valueOf(99.99));
                        assertThat(capturedDto.getStock()).isEqualTo(50);
                        assertThat(capturedDto.isAvailable()).isTrue();
                        assertThat(capturedDto.getStoreId()).isEqualTo(storeId);
                }

                @Test
                @DisplayName("should save outbox event with serialized JSON payload")
                void shouldSaveOutboxEventWithSerializedJsonPayload() throws Exception {
                        // Arrange
                        ProductUpdateEvent event = new ProductUpdateEvent(this, product, "product.created");
                        String jsonPayload = "{\"productId\":\"" + productId + "\",\"name\":\"Test Product\"}";
                        when(objectMapper.writeValueAsString(any(ProductEventDto.class)))
                                        .thenReturn(jsonPayload);

                        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        productEventListener.handleProductUpdateEvent(event);

                        // Assert
                        verify(outboxRepository).save(outboxCaptor.capture());
                        OutboxEvent savedEvent = outboxCaptor.getValue();
                        assertThat(savedEvent.getPayload()).isEqualTo(jsonPayload);
                }

                @Test
                @DisplayName("should mark outbox event as not processed initially")
                void shouldMarkOutboxEventAsNotProcessedInitially() throws Exception {
                        // Arrange
                        ProductUpdateEvent event = new ProductUpdateEvent(this, product, "product.created");
                        when(objectMapper.writeValueAsString(any(ProductEventDto.class)))
                                        .thenReturn("{\"productId\":\"test\"}");

                        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        productEventListener.handleProductUpdateEvent(event);

                        // Assert
                        verify(outboxRepository).save(outboxCaptor.capture());
                        OutboxEvent savedEvent = outboxCaptor.getValue();
                        assertThat(savedEvent.isProcessed()).isFalse();
                }

                @Test
                @DisplayName("should set createdAt timestamp")
                void shouldSetCreatedAtTimestamp() throws Exception {
                        // Arrange
                        ProductUpdateEvent event = new ProductUpdateEvent(this, product, "product.created");
                        when(objectMapper.writeValueAsString(any(ProductEventDto.class)))
                                        .thenReturn("{\"productId\":\"test\"}");

                        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        productEventListener.handleProductUpdateEvent(event);

                        // Assert
                        verify(outboxRepository).save(outboxCaptor.capture());
                        OutboxEvent savedEvent = outboxCaptor.getValue();
                        assertThat(savedEvent.getCreatedAt()).isNotNull();
                }

                @Test
                @DisplayName("should throw RuntimeException when JSON serialization fails")
                void shouldThrowRuntimeExceptionWhenJsonSerializationFails() throws Exception {
                        // Arrange
                        ProductUpdateEvent event = new ProductUpdateEvent(this, product, "product.created");
                        when(objectMapper.writeValueAsString(any(ProductEventDto.class)))
                                        .thenThrow(new JsonProcessingException("Invalid JSON") {
                                        });

                        // Act & Assert
                        assertThatThrownBy(() -> productEventListener.handleProductUpdateEvent(event))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Failed to save outbox event");

                        verify(outboxRepository, never()).save(any());
                }

                @Test
                @DisplayName("should throw RuntimeException when repository save fails")
                void shouldThrowRuntimeExceptionWhenRepositorySaveFails() throws Exception {
                        // Arrange
                        ProductUpdateEvent event = new ProductUpdateEvent(this, product, "product.created");
                        when(objectMapper.writeValueAsString(any(ProductEventDto.class)))
                                        .thenReturn("{\"productId\":\"test\"}");
                        doThrow(new RuntimeException("Database error"))
                                        .when(outboxRepository).save(any(OutboxEvent.class));

                        // Act & Assert
                        assertThatThrownBy(() -> productEventListener.handleProductUpdateEvent(event))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Failed to save outbox event");
                }

                @Test
                @DisplayName("should handle different routing keys correctly")
                void shouldHandleDifferentRoutingKeysCorrectly() throws Exception {
                        // Arrange - test with product.updated
                        ProductUpdateEvent event1 = new ProductUpdateEvent(this, product, "product.updated");
                        when(objectMapper.writeValueAsString(any(ProductEventDto.class)))
                                        .thenReturn("{\"productId\":\"test\"}");

                        // Act
                        productEventListener.handleProductUpdateEvent(event1);

                        // Assert
                        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
                        verify(outboxRepository).save(captor.capture());
                        assertThat(captor.getValue().getType()).isEqualTo("product.updated");

                        // Arrange - test with product.created
                        reset(outboxRepository);
                        ProductUpdateEvent event2 = new ProductUpdateEvent(this, product, "product.created");

                        // Act
                        productEventListener.handleProductUpdateEvent(event2);

                        // Assert
                        verify(outboxRepository).save(captor.capture());
                        assertThat(captor.getValue().getType()).isEqualTo("product.created");
                }
        }

        @Nested
        @DisplayName("Product Delete Event Tests")
        class ProductDeleteEventTests {

                @Test
                @DisplayName("should save outbox event with correct aggregateType PRODUCT")
                void shouldSaveOutboxEventWithCorrectAggregateType() throws Exception {
                        // Arrange
                        ProductDeleteEvent event = new ProductDeleteEvent(this, productId);
                        when(objectMapper.writeValueAsString(productId))
                                        .thenReturn("\"" + productId + "\"");

                        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        productEventListener.handleProductDeleteEvent(event);

                        // Assert
                        verify(outboxRepository).save(outboxCaptor.capture());
                        OutboxEvent savedEvent = outboxCaptor.getValue();
                        assertThat(savedEvent.getAggregateType()).isEqualTo("PRODUCT");
                }

                @Test
                @DisplayName("should save outbox event with productId as aggregateId")
                void shouldSaveOutboxEventWithProductIdAsAggregateId() throws Exception {
                        // Arrange
                        ProductDeleteEvent event = new ProductDeleteEvent(this, productId);
                        when(objectMapper.writeValueAsString(productId))
                                        .thenReturn("\"" + productId + "\"");

                        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        productEventListener.handleProductDeleteEvent(event);

                        // Assert
                        verify(outboxRepository).save(outboxCaptor.capture());
                        OutboxEvent savedEvent = outboxCaptor.getValue();
                        assertThat(savedEvent.getAggregateId()).isEqualTo(productId.toString());
                }

                @Test
                @DisplayName("should save outbox event with type product.deleted")
                void shouldSaveOutboxEventWithTypeProductDeleted() throws Exception {
                        // Arrange
                        ProductDeleteEvent event = new ProductDeleteEvent(this, productId);
                        when(objectMapper.writeValueAsString(productId))
                                        .thenReturn("\"" + productId + "\"");

                        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        productEventListener.handleProductDeleteEvent(event);

                        // Assert
                        verify(outboxRepository).save(outboxCaptor.capture());
                        OutboxEvent savedEvent = outboxCaptor.getValue();
                        assertThat(savedEvent.getType()).isEqualTo("product.deleted");
                }

                @Test
                @DisplayName("should serialize productId as payload")
                void shouldSerializeProductIdAsPayload() throws Exception {
                        // Arrange
                        ProductDeleteEvent event = new ProductDeleteEvent(this, productId);
                        String jsonPayload = "\"" + productId + "\"";
                        when(objectMapper.writeValueAsString(productId))
                                        .thenReturn(jsonPayload);

                        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        productEventListener.handleProductDeleteEvent(event);

                        // Assert
                        verify(objectMapper).writeValueAsString(productId);
                        verify(outboxRepository).save(outboxCaptor.capture());
                        OutboxEvent savedEvent = outboxCaptor.getValue();
                        assertThat(savedEvent.getPayload()).isEqualTo(jsonPayload);
                }

                @Test
                @DisplayName("should mark outbox event as not processed initially")
                void shouldMarkOutboxEventAsNotProcessedInitially() throws Exception {
                        // Arrange
                        ProductDeleteEvent event = new ProductDeleteEvent(this, productId);
                        when(objectMapper.writeValueAsString(productId))
                                        .thenReturn("\"" + productId + "\"");

                        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

                        // Act
                        productEventListener.handleProductDeleteEvent(event);

                        // Assert
                        verify(outboxRepository).save(outboxCaptor.capture());
                        OutboxEvent savedEvent = outboxCaptor.getValue();
                        assertThat(savedEvent.isProcessed()).isFalse();
                }

                @Test
                @DisplayName("should throw RuntimeException when JSON serialization fails")
                void shouldThrowRuntimeExceptionWhenJsonSerializationFails() throws Exception {
                        // Arrange
                        ProductDeleteEvent event = new ProductDeleteEvent(this, productId);
                        when(objectMapper.writeValueAsString(productId))
                                        .thenThrow(new JsonProcessingException("Invalid JSON") {
                                        });

                        // Act & Assert
                        assertThatThrownBy(() -> productEventListener.handleProductDeleteEvent(event))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Failed to save outbox event");

                        verify(outboxRepository, never()).save(any());
                }

                @Test
                @DisplayName("should throw RuntimeException when repository save fails")
                void shouldThrowRuntimeExceptionWhenRepositorySaveFails() throws Exception {
                        // Arrange
                        ProductDeleteEvent event = new ProductDeleteEvent(this, productId);
                        when(objectMapper.writeValueAsString(productId))
                                        .thenReturn("\"" + productId + "\"");
                        doThrow(new RuntimeException("Database error"))
                                        .when(outboxRepository).save(any(OutboxEvent.class));

                        // Act & Assert
                        assertThatThrownBy(() -> productEventListener.handleProductDeleteEvent(event))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Failed to save outbox event");
                }
        }
}
