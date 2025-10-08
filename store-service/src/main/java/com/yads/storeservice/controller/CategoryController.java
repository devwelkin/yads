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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(
            @Valid @RequestBody CategoryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        CategoryResponse createdCategory = categoryService.createCategory(request, ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdCategory);
    }

    @GetMapping("/{categoryId}")
    public ResponseEntity<CategoryResponse> getCategoryById(@PathVariable UUID categoryId) {
        CategoryResponse categoryResponse = categoryService.getCategoryById(categoryId);
        return ResponseEntity.ok(categoryResponse);
    }

    @PatchMapping("/{categoryId}")
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable UUID categoryId,
            @Valid @RequestBody CategoryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        CategoryResponse updatedCategory = categoryService.updateCategory(categoryId, request, ownerId);
        return ResponseEntity.ok(updatedCategory);
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable UUID categoryId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        categoryService.deleteCategory(categoryId, ownerId);
        return ResponseEntity.noContent().build();
    }
}
