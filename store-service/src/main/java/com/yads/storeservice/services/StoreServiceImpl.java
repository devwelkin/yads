package com.yads.storeservice.services;

import com.yads.storeservice.dto.StoreRequest;
import com.yads.common.dto.StoreResponse;
import com.yads.common.exception.AccessDeniedException;
import com.yads.common.exception.DuplicateResourceException;
import com.yads.common.exception.ResourceNotFoundException;
import com.yads.storeservice.mapper.StoreMapper;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.model.StoreType;
import com.yads.storeservice.repository.StoreRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StoreServiceImpl implements StoreService {

    private static final Logger log = LoggerFactory.getLogger(StoreServiceImpl.class);

    private final StoreRepository storeRepository;
    private final StoreMapper storeMapper;

    @Override
    @Transactional
    public StoreResponse createStore(StoreRequest request, Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());

        // Only STORE_OWNER role can create stores
        List<String> roles = extractClientRoles(jwt);
        if (!roles.contains("STORE_OWNER")) {
            log.warn("Access denied: User {} attempted to create store without STORE_OWNER role", ownerId);
            throw new AccessDeniedException("Access Denied: Only approved store owners can create stores");
        }

        // Check if a store with the same name already exists
        if (storeRepository.existsByName(request.getName())) {
            log.warn("Duplicate store creation attempt: name='{}', owner={}", request.getName(), ownerId);
            throw new DuplicateResourceException("Store with name '" + request.getName() + "' already exists");
        }

        Store store = storeMapper.toStore(request);

        store.setOwnerId(ownerId);
        store.setIsActive(true);

        Store savedStore = storeRepository.save(store);
        log.info("Store created: id={}, name='{}', type={}, owner={}",
                savedStore.getId(), savedStore.getName(), savedStore.getStoreType(), ownerId);
        return storeMapper.toStoreResponse(savedStore);
    }

    @Override
    @Transactional
    public StoreResponse getStoreById(UUID storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));
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
    public StoreResponse updateStore(UUID storeId, StoreRequest request, Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());

        // Only STORE_OWNER role can update stores
        List<String> roles = extractClientRoles(jwt);
        if (!roles.contains("STORE_OWNER")) {
            log.warn("Access denied: User {} attempted to update store {} without STORE_OWNER role", ownerId, storeId);
            throw new AccessDeniedException("Access Denied: Only store owners can update stores");
        }

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));
        if (!store.getOwnerId().equals(ownerId)) {
            // Log detailed information for debugging (not sent to client)
            log.warn("Access denied: User {} attempted to update store {} owned by {}",
                    ownerId, storeId, store.getOwnerId());
            // Throw generic message to client (security best practice)
            throw new AccessDeniedException("You are not authorized to update this store");
        }

        // Check if the name is being changed and if the new name already exists
        if (request.getName() != null && !request.getName().equals(store.getName())) {
            if (storeRepository.existsByName(request.getName())) {
                log.warn("Duplicate store name in update: newName='{}', storeId={}", request.getName(), storeId);
                throw new DuplicateResourceException("Store with name '" + request.getName() + "' already exists");
            }
        }

        storeMapper.updateStoreFromRequest(request, store);
        Store updatedStore = storeRepository.save(store);

        log.info("Store updated: id={}, name='{}', owner={}", updatedStore.getId(), updatedStore.getName(), ownerId);
        return storeMapper.toStoreResponse(updatedStore);
    }

    @Override
    @Transactional
    public void deleteStore(UUID storeId, Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());

        // Only STORE_OWNER role can delete stores
        List<String> roles = extractClientRoles(jwt);
        if (!roles.contains("STORE_OWNER")) {
            log.warn("Access denied: User {} attempted to delete store {} without STORE_OWNER role", ownerId, storeId);
            throw new AccessDeniedException("Access Denied: Only store owners can delete stores");
        }

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));
        if (!store.getOwnerId().equals(ownerId)) {
            // Log detailed information for debugging (not sent to client)
            log.warn("Access denied: User {} attempted to delete store {} owned by {}",
                    ownerId, storeId, store.getOwnerId());
            // Throw generic message to client (security best practice)
            throw new AccessDeniedException("You are not authorized to delete this store");
        }
        String storeName = store.getName();
        storeRepository.delete(store);
        log.info("Store deleted: id={}, name='{}', owner={}", storeId, storeName, ownerId);
    }

    /**
     * Extracts client roles from JWT token.
     * Keycloak stores roles under: resource_access.yads-backend.roles
     */
    private List<String> extractClientRoles(Jwt jwt) {
        return Optional.ofNullable(jwt.getClaim("resource_access"))
                .filter(Map.class::isInstance)
                .map(claim -> (Map<?, ?>) claim)
                .map(accessMap -> accessMap.get("yads-backend"))
                .filter(Map.class::isInstance)
                .map(backend -> (Map<?, ?>) backend)
                .map(backendMap -> backendMap.get("roles"))
                .filter(List.class::isInstance)
                .map(roles -> (List<?>) roles)
                .map(list -> list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList())
                .orElse(List.of());
    }
}
