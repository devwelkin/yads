package com.yads.storeservice.controller;

import com.yads.storeservice.dto.StoreRequest;
import com.yads.common.dto.StoreResponse;
import com.yads.storeservice.model.StoreType;
import com.yads.storeservice.services.StoreService;
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
@RequestMapping("/api/v1/stores") // Base path for all endpoints in this controller
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @PostMapping
    public ResponseEntity<StoreResponse> createStore(@Valid @RequestBody StoreRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        StoreResponse createdStore = storeService.createStore(request, ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdStore);
    }

    // All stores lister
    @GetMapping
    public ResponseEntity<List<StoreResponse>> getAllStores(
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) StoreType storeType) {
        List<StoreResponse> stores = storeService.getAllStores(isActive, storeType);
        return ResponseEntity.ok(stores);
    }

    // List owners stores
    @GetMapping("/my-stores")
    public ResponseEntity<List<StoreResponse>> getMyStores(@AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        List<StoreResponse> stores = storeService.getStoresByOwner(ownerId);
        return ResponseEntity.ok(stores);
    }

    @GetMapping("/{storeId}")
    public ResponseEntity<StoreResponse> getStoreById(@PathVariable UUID storeId) {
        StoreResponse storeResponse = storeService.getStoreById(storeId);
        return ResponseEntity.ok(storeResponse);
    }

    @PatchMapping("/{storeId}")
    public ResponseEntity<StoreResponse> updateStore(@PathVariable UUID storeId, @Valid @RequestBody StoreRequest request , @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        StoreResponse updatedStore = storeService.updateStore(storeId, request , ownerId);
        return ResponseEntity.ok(updatedStore);
    }

    @DeleteMapping("/{storeId}")
    public ResponseEntity<Void> deleteStore(@PathVariable UUID storeId , @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        storeService.deleteStore(storeId , ownerId);
        return ResponseEntity.noContent().build();
    }
}

