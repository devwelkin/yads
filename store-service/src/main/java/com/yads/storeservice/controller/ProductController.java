package com.yads.storeservice.controller;

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
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody ProductRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        ProductResponse createdProduct = productService.createProduct(request, ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdProduct);
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable UUID productId) {
        ProductResponse productResponse = productService.getProductById(productId);
        return ResponseEntity.ok(productResponse);
    }

    @GetMapping("/category/{categoryId}")
    public ResponseEntity<List<ProductResponse>> getProductsByCategory(@PathVariable UUID categoryId) {
        List<ProductResponse> products = productService.getProductsByCategory(categoryId);
        return ResponseEntity.ok(products);
    }

    @GetMapping("/category/{categoryId}/available")
    public ResponseEntity<List<ProductResponse>> getAvailableProductsByCategory(@PathVariable UUID categoryId) {
        List<ProductResponse> products = productService.getAvailableProductsByCategory(categoryId);
        return ResponseEntity.ok(products);
    }

    @GetMapping("/search")
    public ResponseEntity<List<ProductResponse>> searchProductsByName(@RequestParam String name) {
        List<ProductResponse> products = productService.searchProductsByName(name);
        return ResponseEntity.ok(products);
    }

    @PatchMapping("/{productId}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        ProductResponse updatedProduct = productService.updateProduct(productId, request, ownerId);
        return ResponseEntity.ok(updatedProduct);
    }

    @PatchMapping("/{productId}/stock")
    public ResponseEntity<ProductResponse> updateStock(
            @PathVariable UUID productId,
            @RequestParam Integer quantity,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        ProductResponse updatedProduct = productService.updateStock(productId, quantity, ownerId);
        return ResponseEntity.ok(updatedProduct);
    }

    @PatchMapping("/{productId}/availability")
    public ResponseEntity<ProductResponse> toggleAvailability(
            @PathVariable UUID productId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        ProductResponse updatedProduct = productService.toggleAvailability(productId, ownerId);
        return ResponseEntity.ok(updatedProduct);
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> deleteProduct(
            @PathVariable UUID productId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        productService.deleteProduct(productId, ownerId);
        return ResponseEntity.noContent().build();
    }
}
