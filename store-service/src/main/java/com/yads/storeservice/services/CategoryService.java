package com.yads.storeservice.services;

import com.yads.storeservice.dto.CategoryRequest;
import com.yads.storeservice.dto.CategoryResponse;

import java.util.UUID;


public interface CategoryService {
    CategoryResponse createCategory(CategoryRequest categoryRequest, UUID ownerId);
    CategoryResponse updateCategory(UUID categoryId, CategoryRequest categoryRequest, UUID ownerId);
    void deleteCategory(UUID categoryId, UUID ownerId);
    CategoryResponse getCategoryById(UUID categoryId);
}
