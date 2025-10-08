package com.yads.storeservice.services;

import com.yads.storeservice.dto.StoreRequest;
import com.yads.storeservice.dto.StoreResponse;
import java.util.List;
import java.util.UUID;

public interface StoreService {
    StoreResponse createStore(StoreRequest request, UUID ownerId);
    StoreResponse getStoreById(UUID storeId);
    List<StoreResponse> getStoresByOwner(UUID ownerId);
    StoreResponse updateStore(UUID storeId, StoreRequest request, UUID ownerId);
    void deleteStore(UUID storeId, UUID ownerId);
}
