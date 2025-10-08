package com.yads.storeservice.services;

import com.yads.storeservice.dto.CategoryRequest;
import com.yads.storeservice.dto.CategoryResponse;

import java.util.List;
import java.util.UUID;


public interface CategoryService {
    // Create a category for a specific store
    CategoryResponse createCategory(UUID storeId, CategoryRequest categoryRequest, UUID ownerId);

    // Get all categories for a specific store
    List<CategoryResponse> getCategoriesByStore(UUID storeId);

    // Get a single category by ID
    CategoryResponse getCategoryById(UUID categoryId);

    // Update category
    CategoryResponse updateCategory(UUID categoryId, CategoryRequest categoryRequest, UUID ownerId);

    // Delete category
    void deleteCategory(UUID categoryId, UUID ownerId);
}
