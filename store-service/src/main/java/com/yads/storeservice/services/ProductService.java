package com.yads.storeservice.services;

import com.yads.common.dto.BatchReserveStockRequest;
import com.yads.common.dto.BatchReserveStockResponse;
import com.yads.common.dto.ReserveStockRequest;
import com.yads.storeservice.dto.ProductRequest;
import com.yads.storeservice.dto.ProductResponse;

import java.util.List;
import java.util.UUID;

public interface ProductService {
    // CRUD Operations
    ProductResponse createProduct(UUID categoryId, ProductRequest request, UUID ownerId);
    ProductResponse updateProduct(UUID productId, ProductRequest request, UUID ownerId);
    void deleteProduct(UUID productId, UUID ownerId);
    ProductResponse getProductById(UUID productId);

    // Listing by Category
    List<ProductResponse> getProductsByCategory(UUID categoryId);
    List<ProductResponse> getAvailableProductsByCategory(UUID categoryId);

    // Listing by Store
    List<ProductResponse> getProductsByStore(UUID storeId);

    // Search
    List<ProductResponse> searchProductsByName(String name);

    // Stock Management
    ProductResponse updateStock(UUID productId, Integer quantity, UUID ownerId);
    ProductResponse toggleAvailability(UUID productId, UUID ownerId);

    // Reserve product and update stock
    ProductResponse reserveProduct(UUID productId, ReserveStockRequest request);

    // Restore stock (called when order is cancelled)
    void restoreStock(UUID productId, Integer quantity, UUID storeId);

    // Batch operations - CRITICAL for preventing N+1 problem
    List<BatchReserveStockResponse> batchReserveStock(BatchReserveStockRequest request);
    void batchRestoreStock(BatchReserveStockRequest request);

}
