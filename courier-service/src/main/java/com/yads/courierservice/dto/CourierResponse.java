package com.yads.courierservice.dto;

import com.yads.courierservice.model.CourierStatus;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class CourierResponse {
    private UUID id;
    private String name;
    private String email;
    private String profileImageUrl;
    private CourierStatus status;
    private String vehiclePlate;
    private String phoneNumber;
    private Boolean isActive;
    private Double currentLatitude;
    private Double currentLongitude;
}

