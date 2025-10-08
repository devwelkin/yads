package com.yads.storeservice.repository;

import com.yads.storeservice.model.Store;
import com.yads.storeservice.model.StoreType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StoreRepository extends JpaRepository<Store, UUID> {
    List<Store> findByOwnerId(UUID ownerId);
    List<Store> findByAddressCity(String city);
    List<Store> findByIsActive(Boolean isActive);
    List<Store> findByStoreType(com.yads.storeservice.model.StoreType storeType);
    List<Store> findByIsActiveAndStoreType(Boolean isActive, StoreType storeType);
    boolean existsByName(String name);
    Optional<Store> findByName(String name);
}
