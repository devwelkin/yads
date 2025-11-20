package com.yads.storeservice.repository;

import com.yads.storeservice.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    // find all products within a specific category
    List<Product> findByCategoryId(UUID categoryId);

    // find all available products in a category
    List<Product> findByCategoryIdAndIsAvailableTrue(UUID categoryId);

    // find all products within a specific store
    List<Product> findByCategoryStoreId(UUID storeId);

    // for a simple search feature
    List<Product> findByNameContainingIgnoreCase(String name);

    @Modifying
    @Query("UPDATE Product p SET p.stock = p.stock - :quantity, p.isAvailable = (CASE WHEN (p.stock - :quantity) > 0 THEN true ELSE false END) WHERE p.id = :id AND p.stock >= :quantity")
    int decreaseStock(@Param("id") UUID id, @Param("quantity") int quantity);
}