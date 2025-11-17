package com.yads.courierservice.repository;

import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CourierRepository extends JpaRepository<Courier, UUID> {

    // Find all available couriers for assignment
    List<Courier> findByStatusAndIsActiveTrue(CourierStatus status);

    // Find all active couriers
    List<Courier> findByIsActiveTrue();

    /**
     * Finds a courier by ID with pessimistic write lock (SELECT ... FOR UPDATE).
     * This prevents other transactions from reading or modifying the courier
     * until the current transaction commits.
     *
     * CRITICAL: This method MUST be called within a @Transactional context.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Courier c WHERE c.id = :id")
    Optional<Courier> findByIdWithLock(@Param("id") UUID id);
}

