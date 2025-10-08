package com.yads.storeservice.services;

import com.yads.storeservice.dto.CategoryRequest;
import com.yads.storeservice.dto.CategoryResponse;
import com.yads.storeservice.exception.AccessDeniedException;
import com.yads.storeservice.exception.ResourceNotFoundException;
import com.yads.storeservice.mapper.CategoryMapper;
import com.yads.storeservice.model.Category;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.repository.CategoryRepository;
import com.yads.storeservice.repository.StoreRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final StoreRepository storeRepository;
    private final CategoryMapper categoryMapper;
    @Override
    @Transactional
    public CategoryResponse createCategory(CategoryRequest categoryRequest,UUID ownerId) {
        // Find store
        Store store = storeRepository.findById(categoryRequest.getStoreId())
                .orElseThrow(() -> new ResourceNotFoundException("Store not found with id: " + categoryRequest.getStoreId()));
        // Store owner check
        if (!store.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException("User is not authorized to create category for this store");
        }
        Category category = categoryMapper.toCategory(categoryRequest);
        category.setStore(store);

        Category savedCategory = categoryRepository.save(category);
        return categoryMapper.toCategoryResponse(savedCategory);
    }

    @Override
    @Transactional
    public CategoryResponse updateCategory(UUID categoryId, CategoryRequest categoryRequest, UUID ownerId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + categoryId));

        // Authorization check
        if (!category.getStore().getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException("User is not authorized to update this category");
        }

        categoryMapper.updateCategoryFromRequest(categoryRequest, category);

        Category updatedCategory = categoryRepository.save(category);
        return categoryMapper.toCategoryResponse(updatedCategory);
    }

    @Override
    @Transactional
    public void deleteCategory(UUID categoryId , UUID ownerId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + categoryId));

        // Authorization check
        if (!category.getStore().getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException("User is not authorized to update this category");
        }

        categoryRepository.delete(category);
    }

    @Override
    @Transactional
    public CategoryResponse getCategoryById(UUID categoryId) {
        return categoryMapper.toCategoryResponse(categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + categoryId)));
    }
}
