package com.yads.storeservice.controller;

import com.yads.common.dto.ReserveStockRequest;
import com.yads.storeservice.dto.ProductRequest;
import com.yads.storeservice.dto.ProductResponse;
import com.yads.storeservice.services.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * NESTED ROUTES - Collections and Creation
     * These routes show the relationship hierarchy
     */

    // Create product under a category
    @PostMapping("/api/v1/categories/{categoryId}/products")
    public ResponseEntity<ProductResponse> createProduct(
            @PathVariable UUID categoryId,
            @Valid @RequestBody ProductRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        ProductResponse createdProduct = productService.createProduct(categoryId, request, ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdProduct);
    }

    // Get all products in a category
    @GetMapping("/api/v1/categories/{categoryId}/products")
    public ResponseEntity<List<ProductResponse>> getProductsByCategory(@PathVariable UUID categoryId) {
        List<ProductResponse> products = productService.getProductsByCategory(categoryId);
        return ResponseEntity.ok(products);
    }

    // Get only available products in a category
    @GetMapping("/api/v1/categories/{categoryId}/products/available")
    public ResponseEntity<List<ProductResponse>> getAvailableProductsByCategory(@PathVariable UUID categoryId) {
        List<ProductResponse> products = productService.getAvailableProductsByCategory(categoryId);
        return ResponseEntity.ok(products);
    }

    // Get all products in a store
    @GetMapping("/api/v1/stores/{storeId}/products")
    public ResponseEntity<List<ProductResponse>> getProductsByStore(@PathVariable UUID storeId) {
        List<ProductResponse> products = productService.getProductsByStore(storeId);
        return ResponseEntity.ok(products);
    }

    /**
     * FLAT ROUTES - Individual Resource Operations
     * Simpler URLs for working with a specific product
     */

    // Get a single product
    @GetMapping("/api/v1/products/{productId}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable UUID productId) {
        ProductResponse productResponse = productService.getProductById(productId);
        return ResponseEntity.ok(productResponse);
    }

    // Update a product
    @PatchMapping("/api/v1/products/{productId}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        ProductResponse updatedProduct = productService.updateProduct(productId, request, ownerId);
        return ResponseEntity.ok(updatedProduct);
    }

    // Delete a product
    @DeleteMapping("/api/v1/products/{productId}")
    public ResponseEntity<Void> deleteProduct(
            @PathVariable UUID productId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        productService.deleteProduct(productId, ownerId);
        return ResponseEntity.noContent().build();
    }

    /**
     * UTILITY ROUTES - Special operations
     */

    // Search products by name
    @GetMapping("/api/v1/products/search")
    public ResponseEntity<List<ProductResponse>> searchProductsByName(@RequestParam String name) {
        List<ProductResponse> products = productService.searchProductsByName(name);
        return ResponseEntity.ok(products);
    }

    // Update stock
    @PatchMapping("/api/v1/products/{productId}/stock")
    public ResponseEntity<ProductResponse> updateStock(
            @PathVariable UUID productId,
            @RequestParam Integer quantity,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        ProductResponse updatedProduct = productService.updateStock(productId, quantity, ownerId);
        return ResponseEntity.ok(updatedProduct);
    }

    // Toggle availability
    @PatchMapping("/api/v1/products/{productId}/availability")
    public ResponseEntity<ProductResponse> toggleAvailability(
            @PathVariable UUID productId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        ProductResponse updatedProduct = productService.toggleAvailability(productId, ownerId);
        return ResponseEntity.ok(updatedProduct);
    }


    @PostMapping("/api/v1/products/{productId}/reserve")
    public ResponseEntity<ProductResponse> reserveProductStock(
            @PathVariable UUID productId,
            @Valid @RequestBody ReserveStockRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        ProductResponse updatedProduct = productService.reserveProduct(productId, request);
        return ResponseEntity.ok(updatedProduct);
    }
}
