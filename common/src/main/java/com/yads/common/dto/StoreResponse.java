package com.yads.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO for inter-service communication regarding Store information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreResponse {
    private UUID id;
    private String name;
    private String description;
    private UUID ownerId;
    private String storeType;
    private Boolean isActive;

    // Address fields for pickup location
    private String street;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private Double latitude;
    private Double longitude;
}

