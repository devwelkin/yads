package com.yads.storeservice.services;

import com.yads.storeservice.dto.StoreRequest;
import com.yads.storeservice.dto.StoreResponse;
import com.yads.storeservice.exception.AccessDeniedException;
import com.yads.storeservice.exception.DuplicateResourceException;
import com.yads.storeservice.exception.ResourceNotFoundException;
import com.yads.storeservice.mapper.StoreMapper;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.model.StoreType;
import com.yads.storeservice.repository.StoreRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StoreServiceImpl implements StoreService{
    private final StoreRepository storeRepository;
    private final StoreMapper storeMapper;

    @Override
    @Transactional
    public StoreResponse createStore(StoreRequest request, UUID ownerId) {
        // Check if a store with the same name already exists
        if (storeRepository.existsByName(request.getName())) {
            throw new DuplicateResourceException("Store with name '" + request.getName() + "' already exists");
        }

        Store store = storeMapper.toStore(request);

        store.setOwnerId(ownerId);
        store.setIsActive(true);

        Store savedStore = storeRepository.save(store);
        return storeMapper.toStoreResponse(savedStore);
    }

    @Override
    @Transactional
    public StoreResponse getStoreById(UUID storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found with id: " + storeId));
        return storeMapper.toStoreResponse(store);
    }

    @Override
    @Transactional
    public List<StoreResponse> getStoresByOwner(UUID ownerId) {
        List<Store> stores = storeRepository.findByOwnerId(ownerId);

        return stores.stream()
                .map(storeMapper::toStoreResponse)
                .toList();
    }

    @Override
    @Transactional
    public List<StoreResponse> getAllStores(Boolean isActive, StoreType storeType) {
        List<Store> stores;

        if (isActive != null && storeType != null) {
            stores = storeRepository.findByIsActiveAndStoreType(isActive, storeType);
        } else if (isActive != null) {
            stores = storeRepository.findByIsActive(isActive);
        } else if (storeType != null) {
            stores = storeRepository.findByStoreType(storeType);
        } else {
            stores = storeRepository.findAll();
        }

        return stores.stream()
                .map(storeMapper::toStoreResponse)
                .toList();
    }

    @Override
    @Transactional
    public StoreResponse updateStore(UUID storeId, StoreRequest request, UUID ownerId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found with id: " + storeId));
        if (!store.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException("User is not authorized to update this store.");
        }

        // Check if name is being changed and if the new name already exists
        if (request.getName() != null && !request.getName().equals(store.getName())) {
            if (storeRepository.existsByName(request.getName())) {
                throw new DuplicateResourceException("Store with name '" + request.getName() + "' already exists");
            }
        }

        storeMapper.updateStoreFromRequest(request, store);
        Store updatedStore = storeRepository.save(store);

        return storeMapper.toStoreResponse(updatedStore);
    }

    @Override
    @Transactional
    public void deleteStore(UUID storeId, UUID ownerId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found with id: " + storeId));
        if (!store.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException("User is not authorized to delete this store.");
        }
        storeRepository.delete(store);
    }

}
