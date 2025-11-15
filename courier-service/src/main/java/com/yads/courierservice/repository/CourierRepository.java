package com.yads.courierservice.repository;

import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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
}

