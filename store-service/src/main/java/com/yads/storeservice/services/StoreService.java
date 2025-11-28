package com.yads.storeservice.services;

import com.yads.storeservice.dto.StoreRequest;
import com.yads.common.dto.StoreResponse;
import com.yads.storeservice.model.StoreType;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

public interface StoreService {
    StoreResponse createStore(StoreRequest request, Jwt jwt);

    StoreResponse getStoreById(UUID storeId);

    List<StoreResponse> getStoresByOwner(UUID ownerId);

    List<StoreResponse> getAllStores(Boolean isActive, StoreType storeType);

    StoreResponse updateStore(UUID storeId, StoreRequest request, Jwt jwt);

    void deleteStore(UUID storeId, Jwt jwt);
}
