package com.yads.storeservice.service;

import com.yads.common.dto.StoreResponse;
import com.yads.common.exception.AccessDeniedException;
import com.yads.common.exception.DuplicateResourceException;
import com.yads.common.exception.ResourceNotFoundException;
import com.yads.storeservice.dto.StoreRequest;
import com.yads.storeservice.mapper.StoreMapper;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.model.StoreType;
import com.yads.storeservice.repository.StoreRepository;
import com.yads.storeservice.services.StoreServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreService Unit Tests")
class StoreServiceImplTest {

  @Mock
  private StoreRepository storeRepository;
  @Mock
  private StoreMapper storeMapper;

  @InjectMocks
  private StoreServiceImpl storeService;

  private UUID ownerId;
  private UUID storeId;
  private Store store;
  private StoreRequest storeRequest;
  private StoreResponse storeResponse;

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
    storeId = UUID.randomUUID();

    store = new Store();
    store.setId(storeId);
    store.setName("Test Store");
    store.setOwnerId(ownerId);
    store.setStoreType(StoreType.RESTAURANT);
    store.setIsActive(true);

    storeRequest = new StoreRequest();
    storeRequest.setName("Test Store");
    storeRequest.setDescription("Test Description");
    storeRequest.setStoreType(StoreType.RESTAURANT);

    storeResponse = StoreResponse.builder()
        .id(storeId)
        .name("Test Store")
        .storeType("RESTAURANT")
        .isActive(true)
        .build();
  }

  @Nested
  @DisplayName("Create Store Tests")
  class CreateStoreTests {

    @Test
    @DisplayName("should create store successfully when name is unique")
    void shouldCreateStoreSuccessfully() {
      // Arrange
      when(storeRepository.existsByName(storeRequest.getName())).thenReturn(false);
      when(storeMapper.toStore(storeRequest)).thenReturn(store);
      when(storeRepository.save(any(Store.class))).thenReturn(store);
      when(storeMapper.toStoreResponse(store)).thenReturn(storeResponse);

      // Act
      StoreResponse result = storeService.createStore(storeRequest, ownerId);

      // Assert
      assertThat(result).isNotNull();
      assertThat(result.getId()).isEqualTo(storeId);
      verify(storeRepository).save(argThat(s -> s.getOwnerId().equals(ownerId) && s.getIsActive()));
    }

    @Test
    @DisplayName("should throw DuplicateResourceException when store name already exists")
    void shouldThrowDuplicateResourceExceptionWhenNameExists() {
      // Arrange
      when(storeRepository.existsByName(storeRequest.getName())).thenReturn(true);

      // Act & Assert
      assertThatThrownBy(() -> storeService.createStore(storeRequest, ownerId))
          .isInstanceOf(DuplicateResourceException.class)
          .hasMessageContaining("already exists");

      verify(storeRepository, never()).save(any());
    }

    @Test
    @DisplayName("should set owner id and active status when creating store")
    void shouldSetOwnerIdAndActiveStatusWhenCreatingStore() {
      // Arrange
      when(storeRepository.existsByName(storeRequest.getName())).thenReturn(false);
      when(storeMapper.toStore(storeRequest)).thenReturn(store);
      when(storeRepository.save(any(Store.class))).thenReturn(store);
      when(storeMapper.toStoreResponse(store)).thenReturn(storeResponse);

      // Act
      storeService.createStore(storeRequest, ownerId);

      // Assert
      verify(storeRepository).save(argThat(s -> s.getOwnerId().equals(ownerId) &&
          s.getIsActive() == true));
    }
  }

  @Nested
  @DisplayName("Get Store Tests")
  class GetStoreTests {

    @Test
    @DisplayName("should get store by id successfully")
    void shouldGetStoreByIdSuccessfully() {
      // Arrange
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(storeMapper.toStoreResponse(store)).thenReturn(storeResponse);

      // Act
      StoreResponse result = storeService.getStoreById(storeId);

      // Assert
      assertThat(result).isNotNull();
      assertThat(result.getId()).isEqualTo(storeId);
    }

    @Test
    @DisplayName("should throw ResourceNotFoundException when store not found")
    void shouldThrowResourceNotFoundExceptionWhenStoreNotFound() {
      // Arrange
      when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

      // Act & Assert
      assertThatThrownBy(() -> storeService.getStoreById(storeId))
          .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("should get stores by owner successfully")
    void shouldGetStoresByOwnerSuccessfully() {
      // Arrange
      List<Store> stores = List.of(store);
      when(storeRepository.findByOwnerId(ownerId)).thenReturn(stores);
      when(storeMapper.toStoreResponse(store)).thenReturn(storeResponse);

      // Act
      List<StoreResponse> result = storeService.getStoresByOwner(ownerId);

      // Assert
      assertThat(result).hasSize(1);
      assertThat(result.get(0).getId()).isEqualTo(storeId);
    }

    @Test
    @DisplayName("should return empty list when owner has no stores")
    void shouldReturnEmptyListWhenOwnerHasNoStores() {
      // Arrange
      when(storeRepository.findByOwnerId(ownerId)).thenReturn(Collections.emptyList());

      // Act
      List<StoreResponse> result = storeService.getStoresByOwner(ownerId);

      // Assert
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("Get All Stores Tests")
  class GetAllStoresTests {

    @Test
    @DisplayName("should get all stores when no filters provided")
    void shouldGetAllStoresWhenNoFiltersProvided() {
      // Arrange
      List<Store> stores = List.of(store);
      when(storeRepository.findAll()).thenReturn(stores);
      when(storeMapper.toStoreResponse(store)).thenReturn(storeResponse);

      // Act
      List<StoreResponse> result = storeService.getAllStores(null, null);

      // Assert
      assertThat(result).hasSize(1);
      verify(storeRepository).findAll();
    }

    @Test
    @DisplayName("should filter stores by isActive when provided")
    void shouldFilterStoresByIsActiveWhenProvided() {
      // Arrange
      List<Store> stores = List.of(store);
      when(storeRepository.findByIsActive(true)).thenReturn(stores);
      when(storeMapper.toStoreResponse(store)).thenReturn(storeResponse);

      // Act
      List<StoreResponse> result = storeService.getAllStores(true, null);

      // Assert
      assertThat(result).hasSize(1);
      verify(storeRepository).findByIsActive(true);
      verify(storeRepository, never()).findAll();
    }

    @Test
    @DisplayName("should filter stores by storeType when provided")
    void shouldFilterStoresByStoreTypeWhenProvided() {
      // Arrange
      List<Store> stores = List.of(store);
      when(storeRepository.findByStoreType(StoreType.RESTAURANT)).thenReturn(stores);
      when(storeMapper.toStoreResponse(store)).thenReturn(storeResponse);

      // Act
      List<StoreResponse> result = storeService.getAllStores(null, StoreType.RESTAURANT);

      // Assert
      assertThat(result).hasSize(1);
      verify(storeRepository).findByStoreType(StoreType.RESTAURANT);
      verify(storeRepository, never()).findAll();
    }

    @Test
    @DisplayName("should filter stores by both isActive and storeType when both provided")
    void shouldFilterStoresByBothIsActiveAndStoreTypeWhenBothProvided() {
      // Arrange
      List<Store> stores = List.of(store);
      when(storeRepository.findByIsActiveAndStoreType(true, StoreType.RESTAURANT))
          .thenReturn(stores);
      when(storeMapper.toStoreResponse(store)).thenReturn(storeResponse);

      // Act
      List<StoreResponse> result = storeService.getAllStores(true, StoreType.RESTAURANT);

      // Assert
      assertThat(result).hasSize(1);
      verify(storeRepository).findByIsActiveAndStoreType(true, StoreType.RESTAURANT);
      verify(storeRepository, never()).findAll();
      verify(storeRepository, never()).findByIsActive(any());
      verify(storeRepository, never()).findByStoreType(any());
    }

    @Test
    @DisplayName("should return empty list when no stores match filters")
    void shouldReturnEmptyListWhenNoStoresMatchFilters() {
      // Arrange
      when(storeRepository.findByIsActive(false)).thenReturn(Collections.emptyList());

      // Act
      List<StoreResponse> result = storeService.getAllStores(false, null);

      // Assert
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("Update Store Tests")
  class UpdateStoreTests {

    @Test
    @DisplayName("should update store successfully when owner is authorized")
    void shouldUpdateStoreSuccessfully() {
      // Arrange
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(storeRepository.save(store)).thenReturn(store);
      when(storeMapper.toStoreResponse(store)).thenReturn(storeResponse);

      // Act
      StoreResponse result = storeService.updateStore(storeId, storeRequest, ownerId);

      // Assert
      assertThat(result).isNotNull();
      verify(storeMapper).updateStoreFromRequest(storeRequest, store);
      verify(storeRepository).save(store);
    }

    @Test
    @DisplayName("should throw AccessDeniedException when owner is different")
    void shouldThrowAccessDeniedExceptionWhenOwnerIsDifferent() {
      // Arrange
      UUID differentOwnerId = UUID.randomUUID();
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

      // Act & Assert
      assertThatThrownBy(() -> storeService.updateStore(storeId, storeRequest, differentOwnerId))
          .isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("not authorized");

      verify(storeRepository, never()).save(any());
    }

    @Test
    @DisplayName("should throw ResourceNotFoundException when store not found")
    void shouldThrowResourceNotFoundExceptionWhenStoreNotFound() {
      // Arrange
      when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

      // Act & Assert
      assertThatThrownBy(() -> storeService.updateStore(storeId, storeRequest, ownerId))
          .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("should throw DuplicateResourceException when new name already exists")
    void shouldThrowDuplicateResourceExceptionWhenNewNameExists() {
      // Arrange
      StoreRequest newNameRequest = new StoreRequest();
      newNameRequest.setName("Different Name");

      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(storeRepository.existsByName("Different Name")).thenReturn(true);

      // Act & Assert
      assertThatThrownBy(() -> storeService.updateStore(storeId, newNameRequest, ownerId))
          .isInstanceOf(DuplicateResourceException.class)
          .hasMessageContaining("already exists");

      verify(storeRepository, never()).save(any());
    }

    @Test
    @DisplayName("should allow update when name unchanged")
    void shouldAllowUpdateWhenNameUnchanged() {
      // Arrange
      StoreRequest sameNameRequest = new StoreRequest();
      sameNameRequest.setName("Test Store");
      sameNameRequest.setDescription("Updated Description");

      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(storeRepository.save(store)).thenReturn(store);
      when(storeMapper.toStoreResponse(store)).thenReturn(storeResponse);

      // Act
      StoreResponse result = storeService.updateStore(storeId, sameNameRequest, ownerId);

      // Assert
      assertThat(result).isNotNull();
      verify(storeRepository, never()).existsByName(any());
      verify(storeRepository).save(store);
    }

    @Test
    @DisplayName("should allow update when name is null")
    void shouldAllowUpdateWhenNameIsNull() {
      // Arrange
      StoreRequest nullNameRequest = new StoreRequest();
      nullNameRequest.setName(null);
      nullNameRequest.setDescription("Updated Description");

      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(storeRepository.save(store)).thenReturn(store);
      when(storeMapper.toStoreResponse(store)).thenReturn(storeResponse);

      // Act
      StoreResponse result = storeService.updateStore(storeId, nullNameRequest, ownerId);

      // Assert
      assertThat(result).isNotNull();
      verify(storeRepository, never()).existsByName(any());
      verify(storeRepository).save(store);
    }
  }

  @Nested
  @DisplayName("Delete Store Tests")
  class DeleteStoreTests {

    @Test
    @DisplayName("should delete store successfully when owner is authorized")
    void shouldDeleteStoreSuccessfully() {
      // Arrange
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

      // Act
      storeService.deleteStore(storeId, ownerId);

      // Assert
      verify(storeRepository).delete(store);
    }

    @Test
    @DisplayName("should throw AccessDeniedException when owner is different")
    void shouldThrowAccessDeniedExceptionWhenOwnerIsDifferent() {
      // Arrange
      UUID differentOwnerId = UUID.randomUUID();
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

      // Act & Assert
      assertThatThrownBy(() -> storeService.deleteStore(storeId, differentOwnerId))
          .isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("not authorized");

      verify(storeRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should throw ResourceNotFoundException when store not found")
    void shouldThrowResourceNotFoundExceptionWhenStoreNotFound() {
      // Arrange
      when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

      // Act & Assert
      assertThatThrownBy(() -> storeService.deleteStore(storeId, ownerId))
          .isInstanceOf(ResourceNotFoundException.class);

      verify(storeRepository, never()).delete(any());
    }
  }
}
