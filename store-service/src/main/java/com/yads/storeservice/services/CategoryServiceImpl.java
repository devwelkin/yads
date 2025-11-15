package com.yads.storeservice.services;

import com.yads.storeservice.dto.CategoryRequest;
import com.yads.storeservice.dto.CategoryResponse;
import com.yads.common.exception.AccessDeniedException;
import com.yads.common.exception.ResourceNotFoundException;
import com.yads.storeservice.mapper.CategoryMapper;
import com.yads.storeservice.model.Category;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.repository.CategoryRepository;
import com.yads.storeservice.repository.StoreRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryServiceImpl.class);

    private final CategoryRepository categoryRepository;
    private final StoreRepository storeRepository;
    private final CategoryMapper categoryMapper;
    @Override
    @Transactional
    public CategoryResponse createCategory(UUID storeId, CategoryRequest categoryRequest, UUID ownerId) {
        // Find store
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));

        // Store owner check
        if (!store.getOwnerId().equals(ownerId)) {
            log.warn("Access denied: User {} attempted to create category in store {} owned by {}",
                    ownerId, storeId, store.getOwnerId());
            throw new AccessDeniedException("You are not authorized to create categories in this store");
        }

        Category category = categoryMapper.toCategory(categoryRequest);
        category.setStore(store);

        Category savedCategory = categoryRepository.save(category);
        log.info("Category created: id={}, name='{}', storeId={}, owner={}",
                savedCategory.getId(), savedCategory.getName(), storeId, ownerId);
        return categoryMapper.toCategoryResponse(savedCategory);
    }

    @Override
    @Transactional
    public List<CategoryResponse> getCategoriesByStore(UUID storeId) {
        // Verify store exists
        storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));

        List<Category> categories = categoryRepository.findByStoreId(storeId);
        return categories.stream()
                .map(categoryMapper::toCategoryResponse)
                .toList();
    }

    @Override
    @Transactional
    public CategoryResponse updateCategory(UUID categoryId, CategoryRequest categoryRequest, UUID ownerId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        // Authorization check
        if (!category.getStore().getOwnerId().equals(ownerId)) {
            log.warn("Access denied: User {} attempted to update category {} in store {} owned by {}",
                    ownerId, categoryId, category.getStore().getId(), category.getStore().getOwnerId());
            throw new AccessDeniedException("You are not authorized to update this category");
        }

        categoryMapper.updateCategoryFromRequest(categoryRequest, category);

        Category updatedCategory = categoryRepository.save(category);
        log.info("Category updated: id={}, name='{}', storeId={}, owner={}",
                categoryId, updatedCategory.getName(), category.getStore().getId(), ownerId);
        return categoryMapper.toCategoryResponse(updatedCategory);
    }

    @Override
    @Transactional
    public void deleteCategory(UUID categoryId , UUID ownerId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        // Authorization check
        if (!category.getStore().getOwnerId().equals(ownerId)) {
            log.warn("Access denied: User {} attempted to delete category {} from store {} owned by {}",
                    ownerId, categoryId, category.getStore().getId(), category.getStore().getOwnerId());
            throw new AccessDeniedException("You are not authorized to delete this category");
        }

        String categoryName = category.getName();
        UUID storeId = category.getStore().getId();
        categoryRepository.delete(category);
        log.info("Category deleted: id={}, name='{}', storeId={}, owner={}", categoryId, categoryName, storeId, ownerId);
    }

    @Override
    @Transactional
    public CategoryResponse getCategoryById(UUID categoryId) {
        return categoryMapper.toCategoryResponse(categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found")));
    }
}
