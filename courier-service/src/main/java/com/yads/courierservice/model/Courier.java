package com.yads.courierservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "couriers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Courier {

    @Id // This IS the Keycloak subject ID ('sub' claim)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CourierStatus status = CourierStatus.OFFLINE;

    @Column(name = "vehicle_plate")
    private String vehiclePlate;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    // Current location (for real-time tracking)
    @Column(name = "current_latitude")
    private Double currentLatitude;

    @Column(name = "current_longitude")
    private Double currentLongitude;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}

