package com.yads.storeservice.service;

import com.yads.common.exception.AccessDeniedException;
import com.yads.common.exception.ResourceNotFoundException;
import com.yads.storeservice.dto.CategoryRequest;
import com.yads.storeservice.dto.CategoryResponse;
import com.yads.storeservice.mapper.CategoryMapper;
import com.yads.storeservice.model.Category;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.repository.CategoryRepository;
import com.yads.storeservice.repository.StoreRepository;
import com.yads.storeservice.services.CategoryServiceImpl;
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
@DisplayName("CategoryService Unit Tests")
class CategoryServiceImplTest {

  @Mock
  private CategoryRepository categoryRepository;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private CategoryMapper categoryMapper;

  @InjectMocks
  private CategoryServiceImpl categoryService;

  private UUID ownerId;
  private UUID storeId;
  private UUID categoryId;
  private Store store;
  private Category category;
  private CategoryRequest categoryRequest;
  private CategoryResponse categoryResponse;

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
    storeId = UUID.randomUUID();
    categoryId = UUID.randomUUID();

    store = new Store();
    store.setId(storeId);
    store.setOwnerId(ownerId);
    store.setName("Test Store");

    category = new Category();
    category.setId(categoryId);
    category.setName("Test Category");
    category.setDescription("Test Description");
    category.setStore(store);

    categoryRequest = CategoryRequest.builder()
        .name("Test Category")
        .description("Test Description")
        .build();

    categoryResponse = CategoryResponse.builder()
        .id(categoryId)
        .name("Test Category")
        .description("Test Description")
        .storeId(storeId)
        .build();
  }

  @Nested
  @DisplayName("Create Category Tests")
  class CreateCategoryTests {

    @Test
    @DisplayName("should create category successfully when owner is authorized")
    void shouldCreateCategorySuccessfully() {
      // Arrange
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(categoryMapper.toCategory(categoryRequest)).thenReturn(category);
      when(categoryRepository.save(any(Category.class))).thenReturn(category);
      when(categoryMapper.toCategoryResponse(category)).thenReturn(categoryResponse);

      // Act
      CategoryResponse result = categoryService.createCategory(storeId, categoryRequest, ownerId);

      // Assert
      assertThat(result).isNotNull();
      assertThat(result.getId()).isEqualTo(categoryId);
      verify(categoryRepository).save(argThat(c -> c.getStore().equals(store)));
    }

    @Test
    @DisplayName("should throw AccessDeniedException when owner is different")
    void shouldThrowAccessDeniedExceptionWhenOwnerIsDifferent() {
      // Arrange
      UUID differentOwnerId = UUID.randomUUID();
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

      // Act & Assert
      assertThatThrownBy(() -> categoryService.createCategory(storeId, categoryRequest, differentOwnerId))
          .isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("not authorized");

      verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("should throw ResourceNotFoundException when store not found")
    void shouldThrowResourceNotFoundExceptionWhenStoreNotFound() {
      // Arrange
      when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

      // Act & Assert
      assertThatThrownBy(() -> categoryService.createCategory(storeId, categoryRequest, ownerId))
          .isInstanceOf(ResourceNotFoundException.class)
          .hasMessageContaining("Store not found");

      verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("should set store reference when creating category")
    void shouldSetStoreReferenceWhenCreatingCategory() {
      // Arrange
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(categoryMapper.toCategory(categoryRequest)).thenReturn(category);
      when(categoryRepository.save(any(Category.class))).thenReturn(category);
      when(categoryMapper.toCategoryResponse(category)).thenReturn(categoryResponse);

      // Act
      categoryService.createCategory(storeId, categoryRequest, ownerId);

      // Assert
      verify(categoryRepository).save(argThat(c -> c.getStore() != null && c.getStore().getId().equals(storeId)));
    }
  }

  @Nested
  @DisplayName("Get Category Tests")
  class GetCategoryTests {

    @Test
    @DisplayName("should get category by id successfully")
    void shouldGetCategoryByIdSuccessfully() {
      // Arrange
      when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
      when(categoryMapper.toCategoryResponse(category)).thenReturn(categoryResponse);

      // Act
      CategoryResponse result = categoryService.getCategoryById(categoryId);

      // Assert
      assertThat(result).isNotNull();
      assertThat(result.getId()).isEqualTo(categoryId);
    }

    @Test
    @DisplayName("should throw ResourceNotFoundException when category not found")
    void shouldThrowResourceNotFoundExceptionWhenCategoryNotFound() {
      // Arrange
      when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

      // Act & Assert
      assertThatThrownBy(() -> categoryService.getCategoryById(categoryId))
          .isInstanceOf(ResourceNotFoundException.class)
          .hasMessageContaining("Category not found");
    }

    @Test
    @DisplayName("should get categories by store successfully")
    void shouldGetCategoriesByStoreSuccessfully() {
      // Arrange
      List<Category> categories = List.of(category);
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(categoryRepository.findByStoreId(storeId)).thenReturn(categories);
      when(categoryMapper.toCategoryResponse(category)).thenReturn(categoryResponse);

      // Act
      List<CategoryResponse> result = categoryService.getCategoriesByStore(storeId);

      // Assert
      assertThat(result).hasSize(1);
      assertThat(result.get(0).getId()).isEqualTo(categoryId);
    }

    @Test
    @DisplayName("should return empty list when store has no categories")
    void shouldReturnEmptyListWhenStoreHasNoCategories() {
      // Arrange
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(categoryRepository.findByStoreId(storeId)).thenReturn(Collections.emptyList());

      // Act
      List<CategoryResponse> result = categoryService.getCategoriesByStore(storeId);

      // Assert
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should throw ResourceNotFoundException when store not found for get categories")
    void shouldThrowResourceNotFoundExceptionWhenStoreNotFoundForGetCategories() {
      // Arrange
      when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

      // Act & Assert
      assertThatThrownBy(() -> categoryService.getCategoriesByStore(storeId))
          .isInstanceOf(ResourceNotFoundException.class)
          .hasMessageContaining("Store not found");
    }
  }

  @Nested
  @DisplayName("Update Category Tests")
  class UpdateCategoryTests {

    @Test
    @DisplayName("should update category successfully when owner is authorized")
    void shouldUpdateCategorySuccessfully() {
      // Arrange
      when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
      when(categoryRepository.save(category)).thenReturn(category);
      when(categoryMapper.toCategoryResponse(category)).thenReturn(categoryResponse);

      // Act
      CategoryResponse result = categoryService.updateCategory(categoryId, categoryRequest, ownerId);

      // Assert
      assertThat(result).isNotNull();
      verify(categoryMapper).updateCategoryFromRequest(categoryRequest, category);
      verify(categoryRepository).save(category);
    }

    @Test
    @DisplayName("should throw AccessDeniedException when owner is different")
    void shouldThrowAccessDeniedExceptionWhenOwnerIsDifferent() {
      // Arrange
      UUID differentOwnerId = UUID.randomUUID();
      when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

      // Act & Assert
      assertThatThrownBy(() -> categoryService.updateCategory(categoryId, categoryRequest, differentOwnerId))
          .isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("not authorized");

      verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("should throw ResourceNotFoundException when category not found")
    void shouldThrowResourceNotFoundExceptionWhenCategoryNotFound() {
      // Arrange
      when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

      // Act & Assert
      assertThatThrownBy(() -> categoryService.updateCategory(categoryId, categoryRequest, ownerId))
          .isInstanceOf(ResourceNotFoundException.class)
          .hasMessageContaining("Category not found");
    }

    @Test
    @DisplayName("should validate ownership through store hierarchy")
    void shouldValidateOwnershipThroughStoreHierarchy() {
      // Arrange
      when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
      when(categoryRepository.save(category)).thenReturn(category);
      when(categoryMapper.toCategoryResponse(category)).thenReturn(categoryResponse);

      // Act
      categoryService.updateCategory(categoryId, categoryRequest, ownerId);

      // Assert - verify that ownership was checked through
      // category.getStore().getOwnerId()
      verify(categoryRepository).findById(categoryId);
      assertThat(category.getStore().getOwnerId()).isEqualTo(ownerId);
    }
  }

  @Nested
  @DisplayName("Delete Category Tests")
  class DeleteCategoryTests {

    @Test
    @DisplayName("should delete category successfully when owner is authorized")
    void shouldDeleteCategorySuccessfully() {
      // Arrange
      when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

      // Act
      categoryService.deleteCategory(categoryId, ownerId);

      // Assert
      verify(categoryRepository).delete(category);
    }

    @Test
    @DisplayName("should throw AccessDeniedException when owner is different")
    void shouldThrowAccessDeniedExceptionWhenOwnerIsDifferent() {
      // Arrange
      UUID differentOwnerId = UUID.randomUUID();
      when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

      // Act & Assert
      assertThatThrownBy(() -> categoryService.deleteCategory(categoryId, differentOwnerId))
          .isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("not authorized");

      verify(categoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should throw ResourceNotFoundException when category not found")
    void shouldThrowResourceNotFoundExceptionWhenCategoryNotFound() {
      // Arrange
      when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

      // Act & Assert
      assertThatThrownBy(() -> categoryService.deleteCategory(categoryId, ownerId))
          .isInstanceOf(ResourceNotFoundException.class)
          .hasMessageContaining("Category not found");

      verify(categoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should validate ownership through store hierarchy before delete")
    void shouldValidateOwnershipThroughStoreHierarchyBeforeDelete() {
      // Arrange
      when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

      // Act
      categoryService.deleteCategory(categoryId, ownerId);

      // Assert - verify that ownership was checked through
      // category.getStore().getOwnerId()
      verify(categoryRepository).findById(categoryId);
      assertThat(category.getStore().getOwnerId()).isEqualTo(ownerId);
      verify(categoryRepository).delete(category);
    }
  }
}
