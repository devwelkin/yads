package com.yads.storeservice.controller;

import com.yads.storeservice.dto.CategoryRequest;
import com.yads.storeservice.dto.CategoryResponse;
import com.yads.storeservice.services.CategoryService;
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
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * NESTED ROUTES - Collections and Creation
     * These routes show the relationship between stores and categories
     */

    @PostMapping("/api/v1/stores/{storeId}/categories")
    public ResponseEntity<CategoryResponse> createCategory(
            @PathVariable UUID storeId,
            @Valid @RequestBody CategoryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        CategoryResponse createdCategory = categoryService.createCategory(storeId, request, ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdCategory);
    }

    @GetMapping("/api/v1/stores/{storeId}/categories")
    public ResponseEntity<List<CategoryResponse>> getCategoriesByStore(@PathVariable UUID storeId) {
        List<CategoryResponse> categories = categoryService.getCategoriesByStore(storeId);
        return ResponseEntity.ok(categories);
    }

    /**
     * FLAT ROUTES - Individual Resource Operations
     * Simpler URLs for working with a specific category
     */

    @GetMapping("/api/v1/categories/{categoryId}")
    public ResponseEntity<CategoryResponse> getCategoryById(@PathVariable UUID categoryId) {
        CategoryResponse categoryResponse = categoryService.getCategoryById(categoryId);
        return ResponseEntity.ok(categoryResponse);
    }

    @PatchMapping("/api/v1/categories/{categoryId}")
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable UUID categoryId,
            @Valid @RequestBody CategoryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        CategoryResponse updatedCategory = categoryService.updateCategory(categoryId, request, ownerId);
        return ResponseEntity.ok(updatedCategory);
    }

    @DeleteMapping("/api/v1/categories/{categoryId}")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable UUID categoryId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        categoryService.deleteCategory(categoryId, ownerId);
        return ResponseEntity.noContent().build();
    }
}
