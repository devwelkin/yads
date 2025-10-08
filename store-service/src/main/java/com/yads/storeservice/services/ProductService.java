package com.yads.storeservice.services;

import com.yads.storeservice.dto.ProductRequest;
import com.yads.storeservice.dto.ProductResponse;

import java.util.List;
import java.util.UUID;

public interface ProductService {
    // CRUD Operations
    ProductResponse createProduct(ProductRequest request, UUID ownerId);
    ProductResponse updateProduct(UUID productId, ProductRequest request, UUID ownerId);
    void deleteProduct(UUID productId, UUID ownerId);
    ProductResponse getProductById(UUID productId);

    // Listing by Category
    List<ProductResponse> getProductsByCategory(UUID categoryId);
    List<ProductResponse> getAvailableProductsByCategory(UUID categoryId);

    // Search
    List<ProductResponse> searchProductsByName(String name);

    // Stock Management
    ProductResponse updateStock(UUID productId, Integer quantity, UUID ownerId);
    ProductResponse toggleAvailability(UUID productId, UUID ownerId);
}
