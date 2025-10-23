package com.yads.orderservice.repository;

import com.yads.orderservice.model.ProductSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface ProductSnapshotRepository extends JpaRepository<ProductSnapshot, UUID> {
}